package com.curso.android.module3.amiibo.repository

import com.curso.android.module3.amiibo.data.local.dao.AmiiboDao
import com.curso.android.module3.amiibo.data.local.entity.AmiiboEntity
import com.curso.android.module3.amiibo.data.remote.api.AmiiboApiService
import com.curso.android.module3.amiibo.data.local.entity.AmiiboDetailEntity
import com.curso.android.module3.amiibo.data.remote.model.AmiiboDetail
import com.curso.android.module3.amiibo.data.remote.model.toDetail
import com.curso.android.module3.amiibo.data.remote.model.toDomainModel
import com.curso.android.module3.amiibo.data.remote.model.toEntities
import com.curso.android.module3.amiibo.data.remote.model.toEntity
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * AMIIBO REPOSITORY - Patrón Repository (Offline-First)
 * ============================================================================
 *
 * El Repository es el intermediario entre las fuentes de datos y el ViewModel.
 * Implementa el patrón "Single Source of Truth" (única fuente de verdad):
 *
 *           ┌─────────────────────────────────────────────────────────┐
 *           │                     REPOSITORY                          │
 *           │                                                         │
 *           │   ┌─────────────┐         ┌─────────────┐             │
 *   API ──>│   │   REMOTE    │ ──────> │   LOCAL     │ ────> UI     │
 *           │   │  (Retrofit) │         │   (Room)    │             │
 *           │   └─────────────┘         └─────────────┘             │
 *           │                                                         │
 *           │   1. Fetch from API                                    │
 *           │   2. Save to DB                                        │
 *           │   3. UI observes DB (Flow)                             │
 *           └─────────────────────────────────────────────────────────┘
 *
 * PRINCIPIO OFFLINE-FIRST:
 * -----------------------
 * 1. La BASE DE DATOS es la única fuente de verdad
 * 2. La UI SIEMPRE lee de la base de datos (nunca directamente de la red)
 * 3. Los datos de red se GUARDAN en la DB antes de mostrarse
 * 4. La app funciona sin conexión (muestra datos en cache)
 *
 * BENEFICIOS:
 * -----------
 * - Experiencia offline: Los datos persisten entre sesiones
 * - Consistencia: Una sola fuente de datos para la UI
 * - Performance: Lecturas rápidas desde DB local
 * - Simplicidad: El ViewModel solo observa el Flow de Room
 *
 * ============================================================================
 */
class AmiiboRepository(
    private val amiiboDao: AmiiboDao,
    private val amiiboApiService: AmiiboApiService
) {

    /**
     * =========================================================================
     * OBSERVAR AMIIBOS (FLUJO REACTIVO)
     * =========================================================================
     *
     * Expone el Flow de Room para que el ViewModel pueda observar.
     *
     * Flow<List<AmiiboEntity>>:
     * - Emite automáticamente cuando los datos cambian
     * - El ViewModel convierte esto a StateFlow para la UI
     * - Nunca termina (es un stream infinito)
     *
     * FLUJO DE DATOS:
     * Room DB ──> Flow ──> ViewModel ──> StateFlow ──> Compose UI
     *
     * Cuando llamamos a refreshAmiibos(), Room detecta los cambios
     * y este Flow emite automáticamente la nueva lista.
     */
    fun observeAmiibos(): Flow<List<AmiiboEntity>> {
        return amiiboDao.getAllAmiibos()
    }

    /**
     * =========================================================================
     * REFRESCAR AMIIBOS (SINCRONIZACIÓN)
     * =========================================================================
     *
     * Descarga datos frescos de la API y los guarda en la base de datos.
     *
     * FLUJO:
     * 1. Llama a la API (Retrofit)
     * 2. Convierte DTOs a Entities
     * 3. Reemplaza todos los datos en Room (transacción atómica)
     * 4. Room notifica automáticamente al Flow observeAmiibos()
     *
     * MANEJO DE ERRORES:
     * - Si la API falla, lanza la excepción
     * - El ViewModel debe manejar esto con try/catch
     * - Los datos en cache permanecen intactos si hay error
     *
     * suspend: Función suspendible (ejecutar en Coroutine)
     *
     * @throws IOException si hay error de red
     * @throws HttpException si el servidor retorna error
     * @throws Exception si hay error de parsing o DB
     */
    suspend fun refreshAmiibos() {
        // 1. Obtener datos de la API
        // Retrofit ejecuta esto en un hilo de background automáticamente
        val response = amiiboApiService.getAllAmiibos()

        // 2. Convertir DTOs a Entities (limitado a 20 resultados)
        // Usamos la función de extensión toEntities() definida en AmiiboDto.kt
        val entities = response.amiibo.take(MAX_AMIIBOS).toEntities()

        // 3. Guardar en la base de datos (reemplaza todo)
        // replaceAll() es una @Transaction que:
        //   a) Elimina todos los registros existentes
        //   b) Inserta los nuevos registros
        // Si algo falla, se hace rollback automático
        amiiboDao.replaceAll(entities)

        // 4. NO necesitamos retornar nada
        // Room notifica automáticamente al Flow de observeAmiibos()
    }

    companion object {
        private const val MAX_AMIIBOS = 20
    }

    /**
     * =========================================================================
     * OBTENER DETALLE DE UN AMIIBO (OFFLINE-FIRST)
     * =========================================================================
     *
     * Obtiene información detallada de un Amiibo. Primero revisa la base de
     * datos local. Si no existe, lo obtiene de la API y lo guarda en cache.
     *
     * FLUJO:
     * 1. Buscar en Room por nombre
     * 2. Si existe -> retornar desde cache
     * 3. Si NO existe -> llamar API, guardar en Room, retornar
     *
     * @param name Nombre del Amiibo a consultar
     * @return AmiiboDetail con toda la información del Amiibo
     * @throws Exception si hay error de red o el Amiibo no existe
     */
    suspend fun getAmiiboDetail(name: String): AmiiboDetail {
        // 1. Buscar en cache local
        val cachedDetail = amiiboDao.getDetailByName(name)
        if (cachedDetail != null) {
            // Retornar desde cache
            return cachedDetail.toDomainModel()
        }

        // 2. No está en cache, obtener de la API
        val response = amiiboApiService.getAmiiboDetail(name)
        val detail = response.amiibo.first().toDetail()

        // 3. Guardar en cache para futuras consultas
        amiiboDao.insertDetail(detail.toEntity())

        return detail
    }

    /**
     * =========================================================================
     * OBTENER CONTEO (UTILIDAD)
     * =========================================================================
     *
     * Útil para verificar si hay datos en cache o mostrar estadísticas.
     */
    fun getAmiiboCount(): Flow<Int> {
        return amiiboDao.getCount()
    }
}

/**
 * ============================================================================
 * ESTRATEGIAS DE SINCRONIZACIÓN ALTERNATIVAS
 * ============================================================================
 *
 * 1. REFRESH ON DEMAND (implementada aquí):
 *    - El usuario o la app decide cuándo sincronizar
 *    - Simple y predecible
 *    - Ideal para datos que no cambian frecuentemente
 *
 * 2. CACHE THEN NETWORK:
 *    ```kotlin
 *    fun getAmiibos(): Flow<List<AmiiboEntity>> = flow {
 *        // Primero emite cache
 *        emitAll(amiiboDao.getAllAmiibos().take(1))
 *        // Luego actualiza desde red
 *        try {
 *            refreshAmiibos()
 *        } catch (e: Exception) {
 *            // Ignora error si hay cache
 *        }
 *    }
 *    ```
 *
 * 3. STALE WHILE REVALIDATE:
 *    ```kotlin
 *    suspend fun getAmiibosWithRevalidation(): Flow<List<AmiiboEntity>> {
 *        // Lanza refresh en background sin bloquear
 *        coroutineScope {
 *            launch { runCatching { refreshAmiibos() } }
 *        }
 *        // Retorna datos de cache inmediatamente
 *        return amiiboDao.getAllAmiibos()
 *    }
 *    ```
 *
 * 4. TIME-BASED CACHE:
 *    ```kotlin
 *    suspend fun getAmiibosIfStale(maxAgeMinutes: Int = 30) {
 *        val lastSync = preferences.getLastSyncTime()
 *        val now = System.currentTimeMillis()
 *        if (now - lastSync > maxAgeMinutes * 60 * 1000) {
 *            refreshAmiibos()
 *            preferences.setLastSyncTime(now)
 *        }
 *    }
 *    ```
 *
 * 5. INCREMENTAL SYNC (para datasets grandes):
 *    ```kotlin
 *    suspend fun syncIncremental() {
 *        val lastModified = preferences.getLastModified()
 *        val newItems = api.getAmiibosModifiedSince(lastModified)
 *        amiiboDao.upsertAll(newItems.toEntities())  // Update or Insert
 *    }
 *    ```
 *
 * ============================================================================
 */
