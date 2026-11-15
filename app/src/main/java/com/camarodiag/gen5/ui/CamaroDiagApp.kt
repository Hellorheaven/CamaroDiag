
bottomBar = {
    NavigationBar(
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        NavigationBarItem(
            selected = currentTab == DiagTab.SETUP,
            onClick = { currentTab = DiagTab.SETUP },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text(if (isFr) "Véhicule" else "Vehicle") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = BgDark,
                selectedTextColor = CamaroAmber,
                indicatorColor = CamaroAmber
            )
        )

        NavigationBarItem(
            selected = currentTab == DiagTab.ADAPTER,
            onClick = { currentTab = DiagTab.ADAPTER },
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
            label = { Text(if (isFr) "Connexion" else "Connect") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = BgDark,
                selectedTextColor = CamaroAmber,
                indicatorColor = CamaroAmber
            )
        )

        NavigationBarItem(
            selected = currentTab == DiagTab.LIVE,
            onClick = { currentTab = DiagTab.LIVE },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            label = { Text(if (isFr) "Données" else "Live") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = BgDark,
                selectedTextColor = CamaroAmber,
                indicatorColor = CamaroAmber
            )
        )

        NavigationBarItem(
            selected = currentTab == DiagTab.DTCS,
            onClick = { currentTab = DiagTab.DTCS },
            icon = { Icon(Icons.Default.Build, contentDescription = null) },
            label = { Text(if (isFr) "Diagnostic" else "DTCs") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = BgDark,
                selectedTextColor = CamaroAmber,
                indicatorColor = CamaroAmber
            )
        )

        NavigationBarItem(
            selected = currentTab in setOf(
                DiagTab.MODULES,
                DiagTab.MILEAGE,
                DiagTab.GUIDE,
                DiagTab.CONTROL,
                DiagTab.SETTINGS
            ),
            onClick = { moreExpanded = true },
            icon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
            label = { Text(if (isFr) "Plus" else "More") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = BgDark,
                selectedTextColor = CamaroAmber,
                indicatorColor = CamaroAmber
            )
        )

        DropdownMenu(
            expanded = moreExpanded,
            onDismissRequest = { moreExpanded = false }
        ) {
            listOf(
                DiagTab.MODULES,
                DiagTab.MILEAGE,
                DiagTab.GUIDE,
                DiagTab.CONTROL,
                DiagTab.SETTINGS
            ).forEach { tab ->
                DropdownMenuItem(
                    text = {
                        Text(if (isFr) tab.labelFr else tab.labelEn)
                    },
                    onClick = {
                        currentTab = tab
                        moreExpanded = false
                    }
                )
            }
        }
    }
}
