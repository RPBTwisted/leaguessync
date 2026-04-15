package com.leaguessync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("leaguessync")
public interface LeaguesSyncConfig extends Config
{
    @ConfigItem(
        keyName = "syncEnabled",
        name = "Enable sync",
        description = "Uncheck to pause all syncing without disabling the plugin.",
        position = 1
    )
    default boolean syncEnabled()
    {
        return true;
    }
}
