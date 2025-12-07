# Amiibo Vault

Una aplicación Android educativa que implementa el patrón **Offline-First** para mostrar una colección de figuras Amiibo.

## Descripción

Amiibo Vault es una aplicación diseñada con fines pedagógicos para demostrar conceptos avanzados de desarrollo Android:

- **MVVM con UiState**: Manejo de estados de carga, éxito y error usando `sealed interfaces`
- **Room Database**: Persistencia local con KSP (Kotlin Symbol Processing)
- **Retrofit + Kotlinx Serialization**: Consumo de API REST y parsing de JSON
- **Coil**: Carga asíncrona de imágenes
- **Koin**: Inyección de dependencias

## Arquitectura Offline-First

La filosofía central de esta aplicación es: **"La Base de Datos es la única fuente de verdad"**.

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│    ┌─────────┐         ┌─────────────┐         ┌─────────────┐    │
│    │   API   │ ──────> │    ROOM     │ ──────> │     UI      │    │
│    │(Retrofit│         │  DATABASE   │         │  (Compose)  │    │
│    └─────────┘         └─────────────┘         └─────────────┘    │
│                              │                        ▲            │
│                              │                        │            │
│                              └── Flow<List<Entity>> ──┘            │
│                                                                     │
│    1. La app SIEMPRE lee datos desde Room (nunca directo de red)   │
│    2. Los datos de red se guardan en Room antes de mostrarse       │
│    3. Room notifica automáticamente a la UI cuando hay cambios     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Flujo de Datos

1. **Al iniciar la app**: El ViewModel observa el `Flow` de Room
2. **Refresh solicitado**: El Repository llama a la API
3. **Datos recibidos**: Se guardan en Room (reemplazando los existentes)
4. **Room notifica**: El `Flow` emite la nueva lista automáticamente
5. **UI actualizada**: Compose se recompone con los nuevos datos

### Beneficios

- **Experiencia offline**: Los datos persisten entre sesiones
- **Consistencia**: Una sola fuente de verdad para la UI
- **Performance**: Lecturas rápidas desde la base de datos local
- **Simplicidad**: El ViewModel solo observa Room, no maneja múltiples fuentes

## Estructura del Proyecto

```
app/src/main/java/com/curso/android/module3/amiibo/
├── AmiiboApplication.kt          # Inicialización de Koin
├── MainActivity.kt               # Entry point de la UI
├── data/
│   ├── local/
│   │   ├── entity/
│   │   │   └── AmiiboEntity.kt   # Modelo de datos para Room
│   │   ├── dao/
│   │   │   └── AmiiboDao.kt      # Data Access Object (queries)
│   │   └── db/
│   │       └── AmiiboDatabase.kt # Configuración de Room
│   └── remote/
│       ├── api/
│       │   └── AmiiboApiService.kt  # Definición de endpoints
│       └── model/
│           └── AmiiboDto.kt      # DTOs para mapear JSON
├── repository/
│   └── AmiiboRepository.kt       # Patrón Repository
├── di/
│   └── AppModule.kt              # Módulo de Koin
└── ui/
    ├── viewmodel/
    │   └── AmiiboViewModel.kt    # ViewModel con UiState
    ├── screens/
    │   └── AmiiboListScreen.kt   # Pantalla principal (Compose)
    └── theme/
        └── Theme.kt              # Tema Material 3
```

## Tecnologías Utilizadas

| Tecnología | Versión | Propósito |
|------------|---------|-----------|
| Kotlin | 2.0.21 | Lenguaje de programación |
| KSP | 2.0.21-1.0.28 | Procesamiento de símbolos (reemplaza KAPT) |
| Room | 2.7.0 | Base de datos local (SQLite) |
| Retrofit | 2.11.0 | Cliente HTTP |
| Kotlinx Serialization | 1.7.3 | Parsing de JSON |
| Coil | 3.0.4 | Carga de imágenes |
| Koin | 4.0.0 | Inyección de dependencias |
| Jetpack Compose | BOM 2024.12.01 | UI declarativa |
| Material 3 | - | Sistema de diseño |

## API Utilizada

La aplicación consume la [AmiiboAPI](https://www.amiiboapi.com/):

```
GET https://www.amiiboapi.com/api/amiibo/
```

Respuesta:
```json
{
  "amiibo": [
    {
      "head": "00000000",
      "tail": "00000002",
      "name": "Mario",
      "gameSeries": "Super Mario",
      "image": "https://..."
    },
    ...
  ]
}
```

## Conceptos Clave Explicados

### 1. Sealed Interface (UiState)

```kotlin
sealed interface AmiiboUiState {
    data object Loading : AmiiboUiState
    data class Success(val amiibos: List<AmiiboEntity>) : AmiiboUiState
    data class Error(val message: String) : AmiiboUiState
}
```

Garantiza que manejemos **todos** los estados posibles en la UI.

### 2. Room con KSP

KSP (Kotlin Symbol Processing) reemplaza a KAPT:
- Hasta 2x más rápido
- Genera código Kotlin nativo
- La versión de KSP **debe coincidir** con la versión de Kotlin

### 3. Flow Reactivo

```kotlin
// En el DAO
@Query("SELECT * FROM amiibos")
fun getAllAmiibos(): Flow<List<AmiiboEntity>>

// En el ViewModel
val amiibos = repository.observeAmiibos()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Room emite automáticamente cuando los datos cambian.

### 4. Koin DI

```kotlin
val appModule = module {
    single { Room.databaseBuilder(...).build() }
    single { get<AmiiboDatabase>().amiiboDao() }
    single { AmiiboRepository(get(), get()) }
    viewModel { AmiiboViewModel(get()) }
}
```

No usa generación de código ni reflexión.

## Cómo Ejecutar

1. Clonar el repositorio
2. Abrir en Android Studio (Hedgehog o superior)
3. Sincronizar Gradle
4. Ejecutar en emulador o dispositivo (API 24+)

## Requisitos

- Android Studio Hedgehog (2023.1.1) o superior
- JDK 17
- Android SDK 36 (compileSdk)
- Dispositivo/Emulador con API 24+ (minSdk)

## Licencia

Este proyecto es de uso educativo.

---

> Este proyecto ha sido generado usando Claude Code y adaptado con fines educativos por Adrián Catalán.
