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

    // Last submitted state — skip the HTTP call if nothing changed
    private Set<Integer> lastTaskIds = new HashSet<>();

    // ── League task IDs ──────────────────────────────────────────────────────
    // Sourced from the OSRS wiki task data (data-taskid attributes).
    // The wiki uses the same IDs supplied by the OSRS team.
    private static final int[] TASK_IDS = {
        529, 1460, 496, 506, 350, 1318, 1297, 535, 494, 546, 765, 942,
        149, 544, 709, 800, 960, 635, 543, 951, 858, 881, 936, 724,
        802, 887, 714, 377, 580, 435, 1572, 894, 395, 545, 806, 1082,
        961, 618, 711, 576, 486, 499, 514, 609, 840, 393, 34, 14,
        98, 461, 888, 4, 306, 641, 705, 591, 707, 778, 575, 925,
        392, 877, 699, 1100, 1590, 526, 29, 1103, 73, 77, 84, 85,
        100, 130, 132, 133, 134, 148, 178, 186, 197, 202, 232, 234,
        238, 239, 244, 411, 412, 443, 462, 474, 495, 658, 664, 666,
        667, 687, 847, 869, 879, 971, 1016, 1017, 1053, 1107, 1119, 1122,
        1134, 1148, 1172, 1185, 1192, 1195, 1272, 1274, 1324, 1332, 1333, 1345,
        1359, 1361, 1363, 1369, 1387, 1459, 1463, 1468, 1473, 1485, 1486, 1487,
        1489, 1507, 1516, 1523, 1555, 1565, 6, 7, 8, 11, 25, 35,
        36, 37, 39, 40, 44, 50, 56, 64, 68, 69, 74, 75,
        76, 78, 79, 91, 117, 119, 122, 123, 128, 129, 138, 139,
        143, 154, 159, 162, 163, 166, 171, 174, 176, 177, 181, 200,
        211, 215, 221, 233, 235, 236, 240, 242, 243, 254, 327, 332,
        351, 363, 364, 365, 366, 369, 371, 375, 376, 398, 404, 408,
        417, 418, 433, 439, 442, 472, 527, 665, 668, 726, 731, 732,
        744, 783, 794, 797, 798, 801, 814, 821, 841, 850, 851, 860,
        868, 927, 934, 949, 976, 977, 978, 981, 982, 985, 987, 990,
        995, 996, 1004, 1005, 1008, 1009, 1011, 1015, 1019, 1032, 1035, 1036,
        1040, 1041, 1043, 1044, 1045, 1046, 1052, 1061, 1072, 1076, 1095, 1096,
        1123, 1137, 1143, 1158, 1160, 1165, 1169, 1174, 1176, 1187, 1188, 1194,
        1271, 1275, 1278, 1280, 1284, 1305, 1319, 1350, 1370, 1372, 1373, 1374,
        1376, 1388, 1389, 1414, 1423, 1424, 1425, 1433, 1453, 1457, 1465, 1471,
        1476, 1477, 1478, 1481, 1488, 1515, 1518, 1531, 1544, 1570, 167, 280,
        282, 402, 407, 460, 1279, 110, 116, 994, 124, 131, 142, 161,
        185, 222, 227, 237, 241, 266, 255, 403, 415, 420, 427, 458,
        1087, 24, 1090, 1091, 1092, 585, 966, 963, 730, 735, 845, 777,
        782, 820, 747, 736, 760, 933, 718, 886, 696, 763, 1371, 923,
        975, 979, 983, 984, 986, 988, 992, 1007, 1012, 1013, 1014, 1033,
        1034, 1039, 1042, 1047, 1157, 1170, 1186, 1190, 20, 32, 1296, 38,
        65, 66, 10, 1415, 1416, 1417, 1418, 1383, 1384, 1375, 1377, 1378,
        80, 1379, 81, 1437, 1480, 1503, 105, 974, 419, 1470, 247, 479,
        481, 261, 281, 283, 1191, 1201, 1202, 1223, 1224, 1242, 1243, 93,
        175, 259, 21, 28, 260, 33, 43, 265, 57, 71, 441, 874,
        1089, 1094, 483, 1099, 586, 587, 588, 720, 856, 857, 859, 812,
        905, 954, 980, 1002, 1037, 1038, 1466, 1171, 1206, 1207, 1208, 1209,
        1210, 1211, 1212, 1213, 1214, 1215, 1216, 1217, 1218, 1219, 1220, 1221,
        1222, 1225, 1226, 1227, 1228, 1229, 1230, 1231, 1232, 1233, 1234, 1235,
        1236, 1237, 1238, 1239, 1240, 1241, 49, 1245, 1246, 1247, 1248, 1249,
        1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 1259, 1260, 1261,
        1262, 1263, 1264, 1265, 1266, 1267, 63, 88, 1273, 1281, 22, 23,
        67, 1419, 1420, 1421, 1385, 1386, 1380, 82, 1381, 83, 1391, 1392,
        1393, 1394, 1395, 1396, 1397, 1398, 1399, 1400, 1401, 1402, 1403, 1404,
        1405, 1406, 1407, 1408, 1409, 1410, 1411, 1412, 1413, 1479, 249, 250,
        115, 825, 843, 921, 956, 1205, 1204, 1268, 70, 1382, 1422, 248,
        188, 991, 1125, 1335, 1431, 140, 172, 196, 228, 315, 357, 425,
        488, 532, 672, 681, 759, 836, 837, 838, 880, 892, 1467, 1521,
        94, 112, 206, 230, 379, 471, 477, 655, 659, 661, 970, 989,
        997, 1062, 1084, 1105, 1110, 1124, 1129, 1132, 1133, 1175, 1193, 1326,
        1328, 1329, 1339, 1454, 1455, 1462, 1464, 1508, 1522, 1550, 1551, 1553,
        1554, 1603, 3, 9, 86, 92, 113, 118, 137, 187, 192, 257,
        258, 263, 276, 279, 285, 287, 292, 298, 346, 394, 459, 670,
        795, 799, 866, 885, 890, 899, 922, 926, 937, 969, 993, 1001,
        1027, 1029, 1031, 1085, 1088, 1106, 1109, 1117, 1168, 1180, 1183, 1322,
        1349, 1491, 1493, 1494, 1500, 1506, 1510, 1511, 1512, 1513, 1528, 1529,
        1589,
    };

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
     *   varpId = taskId / 32
     *   bit    = taskId % 32
     *   complete = (client.getVarpValue(varpId) & (1 << bit)) != 0
     */
    private Set<Integer> readLeagueTasks()
    {
        Set<Integer> completed = new HashSet<>();
        for (int taskId : TASK_IDS)
        {
            int varpId = taskId / 32;
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
