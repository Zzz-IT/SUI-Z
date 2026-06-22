package rikka.sui.server

import rikka.shizuku.server.ClientManager

class SuiClientManager(
    configManager: SuiConfigManager
) : ClientManager<SuiConfigManager>(configManager)
