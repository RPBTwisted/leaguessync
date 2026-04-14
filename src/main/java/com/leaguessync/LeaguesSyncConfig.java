package com.leaguessync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("leaguessync")
public interface LeaguesSyncConfig extends Config
{
    @ConfigItem(
        keyName = "serverUrl",
        name = "Server URL",
        description = "URL of the LeaguesSync server. Change this when deploying publicly.",
        position = 1
    )
    default String serverUrl()
    {
        return "https://api.osrsleaguetracker.com/";
    }

    @ConfigItem(
        keyName = "syncEnabled",
        name = "Enable sync",
        description = "Uncheck to pause all syncing without disabling the plugin.",
        position = 2
    )
    default boolean syncEnabled()
    {
        return true;
    }
}
