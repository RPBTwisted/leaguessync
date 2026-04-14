package com.leaguessync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LeaguesSyncPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(LeaguesSyncPlugin.class);
        RuneLite.main(args);
    }
}
