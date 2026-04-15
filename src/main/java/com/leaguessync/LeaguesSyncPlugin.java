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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@PluginDescriptor(
    name = "LeaguesSync",
    description = "Syncs your Demonic Pacts League task completion to your personal tracker at osrsleaguetracker.com",
    tags = "leagues,sync,tasks,tracker,demonic pacts"
)
public class LeaguesSyncPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;
    @Inject private LeaguesSyncConfig config;

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int SYNC_INTERVAL_SECONDS = 30;
    private static final String SERVER_URL = "https://api.osrsleaguetracker.com/";

    // Last submitted state — skip the HTTP call if nothing changed.
    // Written from the OkHttp callback thread, read from the scheduler thread,
    // so the reference must be volatile.
    private volatile Set<Integer> lastTaskIds = new HashSet<>();

    // ── League task VarPlayer mapping ────────────────────────────────────────
    // Task group N (covering task IDs N*32 .. N*32+31) is stored in the VarPlayer
    // at TASK_GROUP_VARPS[N].  The varp IDs are NOT sequential — there are two
    // gaps in the address space where other game systems live.  Using a direct
    // lookup table avoids reading the wrong varps and producing false positives.
    //
    // Source: RuneLite VarPlayer enum (LEAGUE_TASK_COMPLETED_0 … _61)
    private static final int[] TASK_GROUP_VARPS = {
        // Groups 0–15  (tasks 0–511)      varps 2616–2631
        2616, 2617, 2618, 2619, 2620, 2621, 2622, 2623,
        2624, 2625, 2626, 2627, 2628, 2629, 2630, 2631,
        // ── gap: 2632–2807 used by other game systems ──
        // Groups 16–43 (tasks 512–1407)   varps 2808–2835
        2808, 2809, 2810, 2811, 2812, 2813, 2814, 2815,
        2816, 2817, 2818, 2819, 2820, 2821, 2822, 2823,
        2824, 2825, 2826, 2827, 2828, 2829, 2830, 2831,
        2832, 2833, 2834, 2835,
        // ── gap: 2836–3338 used by other game systems ──
        // Groups 44–47 (tasks 1408–1535)  varps 3339–3342
        3339, 3340, 3341, 3342,
        // ── gap: 3343–4035 used by other game systems ──
        // Groups 48–61 (tasks 1536–1983)  varps 4036–4049
        4036, 4037, 4038, 4039, 4040, 4041, 4042, 4043,
        4044, 4045, 4046, 4047, 4048, 4049,
    };

    private static final int TASK_COUNT = TASK_GROUP_VARPS.length * 32; // 1984

    @Provides
    LeaguesSyncConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LeaguesSyncConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("LeaguesSync started — syncing to {}", SERVER_URL);
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
     * Each task group N maps to TASK_GROUP_VARPS[N].  Within that VarPlayer,
     * bit (taskId % 32) indicates whether the task is complete.
     */
    private Set<Integer> readLeagueTasks()
    {
        Set<Integer> completed = new HashSet<>();
        for (int taskId = 0; taskId < TASK_COUNT; taskId++)
        {
            int group  = taskId / 32;
            int varpId = TASK_GROUP_VARPS[group];
            int bit    = taskId % 32;
            try
            {
                int varpValue = client.getVarpValue(varpId);
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

        String url = SERVER_URL + "sync/" + username;

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
