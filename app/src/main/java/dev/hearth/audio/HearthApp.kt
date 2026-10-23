package dev.hearth.audio

import android.app.Application

class HearthApp : Application() {
    lateinit var library: LibraryRepository
        private set
    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
    }
}
