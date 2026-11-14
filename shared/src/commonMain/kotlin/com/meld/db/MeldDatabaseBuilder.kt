package com.meld.db

import androidx.room3.RoomDatabase

expect fun getMeldDatabaseBuilder(context: Any? = null): RoomDatabase.Builder<MeldDatabase>
