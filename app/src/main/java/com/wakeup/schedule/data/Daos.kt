package com.wakeup.schedule.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeTableDao {
    @Query("SELECT * FROM timetables ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<TimeTableEntity>>

    @Query("SELECT * FROM timetables WHERE id = :id")
    suspend fun getById(id: Long): TimeTableEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(table: TimeTableEntity): Long

    @Update
    suspend fun update(table: TimeTableEntity)

    @Query("DELETE FROM timetables WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM timetables")
    suspend fun count(): Int

    @Query("SELECT id FROM timetables ORDER BY createdAt ASC LIMIT 1")
    suspend fun firstId(): Long?
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE tableId = :tableId ORDER BY id ASC")
    fun getByTableFlow(tableId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE tableId = :tableId ORDER BY id ASC")
    suspend fun getByTable(tableId: Long): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CourseEntity): Long

    @Update
    suspend fun update(course: CourseEntity)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TimeSlotDao {
    @Query("SELECT * FROM time_slots WHERE courseId IN (SELECT id FROM courses WHERE tableId = :tableId)")
    fun getByTableFlow(tableId: Long): Flow<List<TimeSlotEntity>>

    @Query("SELECT * FROM time_slots WHERE courseId = :courseId")
    suspend fun getByCourse(courseId: Long): List<TimeSlotEntity>

    @Query("SELECT * FROM time_slots WHERE courseId IN (SELECT id FROM courses WHERE tableId = :tableId)")
    suspend fun getByTable(tableId: Long): List<TimeSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(slot: TimeSlotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<TimeSlotEntity>)

    @Query("DELETE FROM time_slots WHERE courseId = :courseId")
    suspend fun deleteByCourse(courseId: Long)
}

@Dao
interface SectionTimeDao {
    @Query("SELECT * FROM section_times WHERE tableId = :tableId ORDER BY section ASC")
    fun getByTableFlow(tableId: Long): Flow<List<SectionTimeEntity>>

    @Query("SELECT * FROM section_times WHERE tableId = :tableId ORDER BY section ASC")
    suspend fun getByTable(tableId: Long): List<SectionTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SectionTimeEntity>)

    @Query("SELECT COUNT(*) FROM section_times WHERE tableId = :tableId")
    suspend fun countForTable(tableId: Long): Int
}
