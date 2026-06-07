package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.CounterRepository

class CounterApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { CounterRepository(database.counterDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        private var instance: CounterApplication? = null

        fun getRepository(): CounterRepository {
            return instance?.repository ?: throw IllegalStateException("Application is not initialized yet")
        }
    }
}
