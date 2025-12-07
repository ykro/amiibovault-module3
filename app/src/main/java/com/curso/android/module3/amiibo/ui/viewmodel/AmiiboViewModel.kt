package com.curso.android.module3.amiibo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curso.android.module3.amiibo.data.local.entity.AmiiboEntity
import com.curso.android.module3.amiibo.repository.AmiiboRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * AMIIBO UISTATE - Estado de la UI (Sealed Interface)
 * ============================================================================
 *
 * UiState representa todos los posibles estados de la pantalla.
 * Usar sealed interface garantiza que manejemos TODOS los casos en la UI.
 *
 * PATRÓN UISTATE:
 * ---------------
 * En lugar de tener múltiples variables separadas:
 * ```kotlin
 * // ❌ Antipatrón
 * val isLoading: Boolean
 * val error: String?
 * val data: List<Amiibo>?
 * ```
 *
 * Usamos un solo estado que representa TODAS las posibilidades:
 * ```kotlin
 * // ✅ Patrón UiState
 * sealed interface UiState {
 *     object Loading : UiState
 *     data class Success(val data: List<Amiibo>) : UiState
 *     data class Error(val message: String) : UiState
 * }
 * ```
 *
 * BENEFICIOS:
 * -----------
 * 1. Type-safe: El compilador verifica que manejemos todos los casos
 * 2. Exclusividad: Solo puede estar en UN estado a la vez
 * 3. Claridad: El código de UI es más legible con when exhaustivo
 * 4. Testeable: Fácil de verificar estados en tests
 *
 * SEALED INTERFACE VS SEALED CLASS:
 * ---------------------------------
 * - sealed interface: Más flexible, permite herencia múltiple
 * - sealed class: Puede tener constructor con parámetros comunes
 * - En Kotlin moderno, sealed interface es preferido
 *
 * ============================================================================
 */
sealed interface AmiiboUiState {
    /**
     * Estado de carga inicial.
     * Se muestra cuando:
     * - La app inicia por primera vez
     * - No hay datos en cache
     * - Se está descargando datos
     *
     * data object: Singleton inmutable (Kotlin 1.9+)
     * Equivalente a: object Loading : AmiiboUiState
     */
    data object Loading : AmiiboUiState

    /**
     * Estado de éxito con datos.
     * Se muestra cuando hay datos para mostrar (de cache o red).
     *
     * @param amiibos Lista de Amiibos a mostrar
     * @param isRefreshing True si se está actualizando en background
     *
     * isRefreshing permite mostrar un indicador de actualización
     * mientras se muestran los datos existentes (pull-to-refresh pattern)
     */
    data class Success(
        val amiibos: List<AmiiboEntity>,
        val isRefreshing: Boolean = false
    ) : AmiiboUiState

    /**
     * Estado de error.
     * Se muestra cuando:
     * - Falla la llamada a la API
     * - No hay conexión a internet
     * - Error de parsing o base de datos
     *
     * @param message Mensaje de error para mostrar al usuario
     * @param cachedAmiibos Datos en cache (si existen) para mostrar junto al error
     *
     * Incluir cachedAmiibos permite mostrar datos viejos con un banner de error,
     * en lugar de una pantalla de error completa.
     */
    data class Error(
        val message: String,
        val cachedAmiibos: List<AmiiboEntity> = emptyList()
    ) : AmiiboUiState
}

/**
 * ============================================================================
 * AMIIBO VIEWMODEL - Lógica de Presentación
 * ============================================================================
 *
 * El ViewModel es el intermediario entre la UI y los datos.
 * Responsabilidades:
 * 1. Exponer el estado de la UI (UiState)
 * 2. Manejar acciones del usuario (refresh)
 * 3. Transformar datos del Repository para la UI
 * 4. Sobrevivir a configuration changes (rotación)
 *
 * ARQUITECTURA MVVM:
 * ------------------
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │                                                                 │
 *   │    ┌──────────┐         ┌──────────────┐       ┌──────────┐   │
 *   │    │   VIEW   │ ◄────── │  VIEWMODEL   │ ◄──── │  MODEL   │   │
 *   │    │ (Compose)│         │              │       │ (Repo)   │   │
 *   │    └────┬─────┘         └──────────────┘       └──────────┘   │
 *   │         │                      ▲                              │
 *   │         │    User Actions      │                              │
 *   │         └──────────────────────┘                              │
 *   │                                                                 │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * FLUJO DE DATOS (Unidirectional Data Flow):
 * 1. UI observa uiState (StateFlow)
 * 2. Usuario hace acción → llama a función del ViewModel
 * 3. ViewModel actualiza el estado
 * 4. UI se recompone automáticamente con el nuevo estado
 *
 * ============================================================================
 */
class AmiiboViewModel(
    private val repository: AmiiboRepository
) : ViewModel() {

    /**
     * Estado interno mutable.
     * Solo el ViewModel puede modificar este estado.
     */
    private val _uiState = MutableStateFlow<AmiiboUiState>(AmiiboUiState.Loading)

    /**
     * Estado público inmutable para la UI.
     *
     * StateFlow:
     * - Similar a LiveData pero de Kotlin Coroutines
     * - Siempre tiene un valor (no nullable)
     * - La UI se suscribe y recibe actualizaciones automáticas
     * - Ideal para Jetpack Compose
     *
     * asStateFlow(): Convierte MutableStateFlow a StateFlow inmutable
     */
    val uiState: StateFlow<AmiiboUiState> = _uiState.asStateFlow()

    /**
     * Flow de amiibos desde la base de datos.
     *
     * stateIn(): Convierte Flow a StateFlow
     * - viewModelScope: Se cancela cuando el ViewModel se destruye
     * - SharingStarted.WhileSubscribed(5000): Mantiene activo 5s después
     *   de que el último suscriptor se va (optimización para rotación)
     * - emptyList(): Valor inicial mientras se carga
     */
    private val amiibosFromDb: StateFlow<List<AmiiboEntity>> = repository
        .observeAmiibos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Inicialización del ViewModel.
     *
     * init { } se ejecuta cuando se crea el ViewModel.
     * Aquí configuramos la observación de datos y cargamos inicialmente.
     */
    init {
        // Observar cambios en la base de datos
        observeDatabaseChanges()
        // Cargar datos iniciales
        refreshAmiibos()
    }

    /**
     * =========================================================================
     * OBSERVAR CAMBIOS EN LA BASE DE DATOS
     * =========================================================================
     *
     * Configura la observación reactiva del Flow de Room.
     * Cada vez que los datos cambian, actualiza el UiState.
     */
    private fun observeDatabaseChanges() {
        viewModelScope.launch {
            amiibosFromDb.collect { amiibos ->
                // Solo actualiza a Success si hay datos o no estamos en Loading inicial
                val currentState = _uiState.value
                if (amiibos.isNotEmpty()) {
                    _uiState.value = AmiiboUiState.Success(
                        amiibos = amiibos,
                        isRefreshing = currentState is AmiiboUiState.Success &&
                                (currentState as? AmiiboUiState.Success)?.isRefreshing == true
                    )
                }
            }
        }
    }

    /**
     * =========================================================================
     * REFRESCAR AMIIBOS
     * =========================================================================
     *
     * Descarga datos frescos de la API.
     * Llamado desde:
     * - init {} al iniciar
     * - Pull-to-refresh de la UI
     * - Botón de reintentar en caso de error
     *
     * MANEJO DE ESTADOS:
     * 1. Si hay datos existentes → Success con isRefreshing = true
     * 2. Si no hay datos → Loading
     * 3. En éxito → Success (automático por el Flow de Room)
     * 4. En error → Error con datos en cache si existen
     */
    fun refreshAmiibos() {
        viewModelScope.launch {
            // Determinar estado durante la carga
            val currentAmiibos = amiibosFromDb.value
            if (currentAmiibos.isEmpty()) {
                // No hay cache, mostrar loading
                _uiState.value = AmiiboUiState.Loading
            } else {
                // Hay cache, mostrar datos con indicador de refresh
                _uiState.value = AmiiboUiState.Success(
                    amiibos = currentAmiibos,
                    isRefreshing = true
                )
            }

            try {
                // Llamar al repositorio para refrescar datos
                // Esto actualiza Room, que dispara el Flow observado arriba
                repository.refreshAmiibos()

                // El estado Success se actualiza automáticamente por observeDatabaseChanges()
                // Solo necesitamos quitar el indicador de refreshing
                val updatedAmiibos = amiibosFromDb.value
                _uiState.value = AmiiboUiState.Success(
                    amiibos = updatedAmiibos,
                    isRefreshing = false
                )

            } catch (e: Exception) {
                // Error: mostrar mensaje con datos en cache si existen
                val cachedAmiibos = amiibosFromDb.value
                _uiState.value = AmiiboUiState.Error(
                    message = e.message ?: "Error desconocido al cargar datos",
                    cachedAmiibos = cachedAmiibos
                )
            }
        }
    }
}

/**
 * ============================================================================
 * NOTAS ADICIONALES SOBRE VIEWMODELS
 * ============================================================================
 *
 * 1. viewModelScope:
 *    - Scope de coroutines ligado al lifecycle del ViewModel
 *    - Se cancela automáticamente cuando el ViewModel se destruye
 *    - Usa Dispatchers.Main por defecto
 *
 * 2. SavedStateHandle (para preservar estado en process death):
 *    ```kotlin
 *    class MyViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
 *        val searchQuery = savedStateHandle.getStateFlow("query", "")
 *
 *        fun updateQuery(query: String) {
 *            savedStateHandle["query"] = query
 *        }
 *    }
 *    ```
 *
 * 3. Parámetros de navegación con Koin:
 *    ```kotlin
 *    // En el módulo:
 *    viewModel { (id: String) -> DetailViewModel(id, get()) }
 *
 *    // En Compose:
 *    val viewModel: DetailViewModel = koinViewModel { parametersOf(amiiboId) }
 *    ```
 *
 * 4. Múltiples Flows combinados:
 *    ```kotlin
 *    val uiState = combine(
 *        amiibosFlow,
 *        searchQueryFlow,
 *        sortOrderFlow
 *    ) { amiibos, query, sort ->
 *        amiibos.filter { it.name.contains(query) }
 *               .sortedBy { if (sort == "name") it.name else it.gameSeries }
 *    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 *    ```
 *
 * ============================================================================
 */
