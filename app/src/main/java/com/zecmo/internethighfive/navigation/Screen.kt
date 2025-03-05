package com.zecmo.internethighfive.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Lobby : Screen("lobby")
    object HighFive : Screen("highfive")
    object Friends : Screen("friends")
    object Profile : Screen("profile")
    object AddUser : Screen("adduser")
} 