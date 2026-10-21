package com.invernadero.invernaderoiot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "invernadero.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, salt BLOB NOT NULL, hash BLOB NOT NULL)")
        createReadings(db)
    }

    private fun createReadings(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER,
           temp REAL, soil REAL, tank REAL, volume REAL, total REAL)""")

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS readings"); createReadings(db)
    }

    fun register(user: String, pw: String): Boolean {
        val u = user.trim().lowercase()
        readableDatabase.rawQuery("SELECT 1 FROM users WHERE username=?", arrayOf(u)).use { if (it.moveToFirst()) return false }
        val salt = Crypto.newSalt()
        val cv = ContentValues().apply { put("username", u); put("salt", salt); put("hash", Crypto.hashPassword(pw, salt)) }
        return writableDatabase.insert("users", null, cv) != -1L
    }

    fun login(user: String, pw: String): Boolean {
        readableDatabase.rawQuery("SELECT salt, hash FROM users WHERE username=?",
            arrayOf(user.trim().lowercase())).use {
            if (!it.moveToFirst()) { Crypto.hashPassword(pw, ByteArray(16)); return false }
            return Crypto.verify(pw, it.getBlob(0), it.getBlob(1))
        }
    }

    fun insertReading(s: Snapshot) {
        val cv = ContentValues().apply {
            put("ts", s.ts); put("temp", s.temp); put("soil", s.soil); put("tank", s.tank)
            put("volume", s.volume); put("total", s.total)
        }
        writableDatabase.insert("readings", null, cv)
    }

    fun lastReadings(n: Int): List<Snapshot> {
        val out = mutableListOf<Snapshot>()
        readableDatabase.rawQuery("SELECT ts,temp,soil,tank,volume,total FROM readings ORDER BY id DESC LIMIT ?",
            arrayOf(n.toString())).use {
            while (it.moveToNext()) out += Snapshot(ts = it.getLong(0), temp = it.getDouble(1), soil = it.getDouble(2),
                tank = it.getDouble(3), volume = it.getDouble(4), total = it.getDouble(5))
        }
        return out
    }
}