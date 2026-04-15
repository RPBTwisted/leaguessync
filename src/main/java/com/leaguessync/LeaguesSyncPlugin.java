/*
 * LeaguesSync — a lightweight RuneLite plugin for syncing Leagues task
 * completion to a personal task tracker.
 *
 * Derived in part from WikiSync (https://github.com/weirdgloop/WikiSync)
 * by andmcadams, used under BSD-2-Clause licence.
 */
package com.leaguessync;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@PluginDescriptor(
    name = "LeaguesSync",
    description = "Syncs your Leagues task completion to your personal task tracker",
    tags = "leagues,sync,tasks,tracker"
)
public class LeaguesSyncPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;
    @Inject private LeaguesSyncConfig config;

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int SYNC_INTERVAL_SECONDS = 30;

    // Last submitted state — skip the HTTP call if nothing changed.
    // Written from the OkHttp callback thread, read from the scheduler thread,
    // so the reference must be volatile.
    private volatile Set<Integer> lastTaskIds = new HashSet<>();

    // ── League task ID range ─────────────────────────────────────────────────
    // Task IDs are sequential 0–(TASK_COUNT-1), assigned by Jagex.
    // Each maps to a VarPlayer: varpId = VARP_BASE + taskId / 32, bit = taskId % 32.
    // LEAGUE_TASK_COMPLETED_N is stored at varp (2616 + N), confirmed via dev console:
    //   completing task 68 (group 2, bit 4) changed LEAGUE_TASK_COMPLETED_2(2618).
    private static final int TASK_COUNT = 1592;
    private static final int LEAGUE_TASK_COMPLETED_VARP_BASE = 2616;

    // Varp offsets (relative to VARP_BASE) that are NOT league-task storage —
    // they belong to other game systems and return non-zero data that would
    // otherwise produce false positives.  Confirmed empirically:
    //   offset 16 (varp 2632) — bits 0,1 set  → tasks 512,513 falsely flagged
    //   offset 31 (varp 2647) — bits 6,16 set → tasks 998,1008 falsely flagged
    // Fully-set varps (value == -1) are caught separately in the read loop.
    private static final Set<Integer> EXCLUDED_VARP_OFFSETS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(16, 31)));

    @Provides
    LeaguesSyncConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LeaguesSyncConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("LeaguesSync started — syncing to {}", config.serverUrl());
    }

    @Override
    protected void shutDown()
    {
        lastTaskIds.clear();
        log.info("LeaguesSync stopped.");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case HOPPING:
            case LOGGING_IN:
            case LOGIN_SCREEN:
                // Clear cached state on profile switch so we re-submit on next login
                lastTaskIds.clear();
                break;
            default:
                break;
        }
    }

    // ── Scheduled sync ───────────────────────────────────────────────────────

    @Schedule(period = SYNC_INTERVAL_SECONDS, unit = ChronoUnit.SECONDS)
    public void scheduledSync()
    {
        if (!config.syncEnabled())                       return;
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (client.getLocalPlayer() == null)             return;

        String username = client.getLocalPlayer().getName();

        Set<Integer> completedTasks = readLeagueTasks();
        if (!completedTasks.equals(lastTaskIds))
        {
            submitSync(username, completedTasks);
        }
    }

    // ── Reading game data ────────────────────────────────────────────────────

    /**
     * Reads completed league task IDs from the game client.
     *
     * League tasks are stored as bits packed into VarPlayers:
     *   varpId = LEAGUE_TASK_COMPLETED_VARP_BASE + taskId / 32
     *   bit    = taskId % 32
     *   complete = (client.getVarpValue(varpId) & (1 << bit)) != 0
     *
     * Varps that return -1 (all 32 bits set) are not league-task varps — they contain
     * data from other game systems and must be skipped to avoid false positives.
     */
    private Set<Integer> readLeagueTasks()
    {
        Set<Integer> completed = new HashSet<>();
        for (int taskId = 0; taskId < TASK_COUNT; taskId++)
        {
            int varpOffset = taskId / 32;
            if (EXCLUDED_VARP_OFFSETS.contains(varpOffset)) continue;
            int varpId = LEAGUE_TASK_COMPLETED_VARP_BASE + varpOffset;
            int bit    = taskId % 32;
            try
            {
                int varpValue = client.getVarpValue(varpId);
                if (varpValue == -1) continue;  // not a league-task varp
                if ((varpValue & (1 << bit)) != 0)
                {
                    completed.add(taskId);
                }
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                log.debug("Could not read varp {} for task {}: {}", varpId, taskId, e.getMessage());
            }
        }
        return completed;
    }

    // ── HTTP submission ──────────────────────────────────────────────────────

    private void submitSync(String username, Set<Integer> leagueTasks)
    {
        Map<String, Object> payload = new HashMap<>();
        payload.put("league_tasks", leagueTasks);

        String url = config.serverUrl();
        if (!url.endsWith("/")) url += "/";
        url += "sync/" + username;

        final Set<Integer> snapshot = new HashSet<>(leagueTasks);

        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON_TYPE, gson.toJson(payload)))
            .build();

        log.debug("LeaguesSync: submitting for {}", username);

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("LeaguesSync: submit failed — {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try
                {
                    if (response.isSuccessful())
                    {
                        lastTaskIds = snapshot;
                        log.debug("LeaguesSync: synced {} OK", username);
                    }
                    else
                    {
                        log.debug("LeaguesSync: server returned {}", response.code());
                    }
                }
                finally
                {
                    response.close();
                }
            }
        });
    }
}
