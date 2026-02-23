package com.dalapenko.laba.navigation

import kotlinx.serialization.Serializable

@Serializable
object Library

@Serializable
data class Player(val bookId: Long, val autoPlay: Boolean = true)

@Serializable
object Settings
