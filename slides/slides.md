---
marp: true
theme: default
paginate: true
backgroundColor: #fff
style: |
  section { font-family: 'Inter', sans-serif; }
  h1 { color: #2E7D32; }
  h2 { color: #1B5E20; }
  code { background-color: #f0f0f0; padding: 0.2em; border-radius: 4px; }
  pre { background-color: #f5f5f5; border-radius: 8px; }
  .center { text-align: center; }
  .small { font-size: 0.8em; }
---

<!-- _class: lead -->
<!-- _class: lead -->
# Module 3: Architecture & Persistence
## MVVM, Room, Retrofit & Coil

---

# Amiibo Vault App

This application demonstrates an **Offline-First** architecture. It fetches Amiibo data, stores it locally, and displays it even without an internet connection.

| Home Screen | Detail Screen |
|:-----------:|:-------------:|
| ![h:400](../assets/screenshot_home.png) | ![h:400](../assets/screenshot_detail.png) |

---

# Module Agenda

1. **MVVM & Advanced Architecture**
2. **Room Database**
3. **Networking**
4. **Deep Dive (Internals)**
5. **Challenge Lab**

---

<!-- _class: lead -->
# 1. Advanced Architecture
## Unidirectional Data Flow (UDF) & State

---

## The "Fragile" UI Problem

**Traditional Android (View System / Early Compose):**
- UI components manage their own state.
- Data is scattered: `isLoading` in Activity, `data` in Adapter, `error` in a Toast.
- **Race conditions**: What if `isLoading` is true but `error` is also not null?

**Consequence:**
- Inconsistent UI states.
- Hard to debug.
- Difficult to test.

---

## The Solution: Unidirectional Data Flow

**Core Principle:** State flows **DOWN**, Events flow **UP**.

```text
    +-----------------------+
    |      UI Layer         |  <-- Renders State
    | (Activity / Composable)|  --> Emits Events
    +-----------------------+
          ^          |
    State |          | Events (Clicks)
          |          v
    +-----------------------+
    |   Presentation Layer  |  <-- Process Events
    |      (ViewModel)      |  --> Updates State
    +-----------------------+
          ^          |
    Data  |          | Calls
          |          v
    +-----------------------+
    |      Data Layer       |  <-- Single Source of Truth
    | (Repository / Source) |
    +-----------------------+
```

---

## UiState Pattern: The Golden Rule

**Rule:** The UI state should be a single, immutable snapshot of the screen at a specific point in time.

**Why Sealed Interfaces?**
- **Exhaustiveness**: The compiler forces you to handle ALL states.
- **Readability**: You see all possible UI configurations in one place.
- **Type Safety**: `Success` state guarantees data is present.

---

## Live Code: Defining UiState

**Bad Approach:**
```kotlin
class MyViewModel : ViewModel() {
    val isLoading = MutableStateFlow(false)
    val data = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
}
// Possible invalid state: isLoading=true AND data="Wait what?"
```

**Good Approach (Sealed Interface):**
```kotlin
sealed interface AmiiboUiState {
    data object Loading : AmiiboUiState
    data class Success(val amiibos: List<Amiibo>) : AmiiboUiState
    data class Error(val message: String) : AmiiboUiState
}
```

---

## StateFlow vs SharedFlow vs LiveData

**LiveData:**
- **Legacy** (Avoid in new Kotlin projects).
- Tied to Main Thread explicitly.

**StateFlow:**
- **State holder**. Always has a value (requires `initialValue`).
- **Hot stream**: Active only when collected? No, always holds value.
- Replaces LiveData for **UI State**.

**SharedFlow:**
- **Event stream**. Can have 0 subscribers.
- No initial value.
- Drop older events if buffer full.
- **Use for One-off Events** (Navigation, Snackbars).

---

## How to Expose State Safely

Always expose `StateFlow` as read-only.

```kotlin
class AmiiboViewModel : ViewModel() {
    
    // 1. Private Mutable State
    private val _uiState = MutableStateFlow<AmiiboUiState>(AmiiboUiState.Loading)
    
    // 2. Public Immutable State
    val uiState = _uiState.asStateFlow()
    
    fun loadData() {
        _uiState.update { AmiiboUiState.Loading } 
        // .update is atomic and thread-safe!
    }
}
```

---

## MVI vs MVVM (Nuance)

**MVVM (Model-View-ViewModel):**
- Focus on state binding.
- ViewModel exposes multiple observables.

**MVI (Model-View-Intent):**
- Strict single state object.
- **Intents** (Actions) are objects too.

**Modern Android approach:**
- It is a hybrid.
- We use **ViewModel** (MVVM) but with **Single State** (MVI-ish).
- We conceptually treat user actions as "Events" processed by the ViewModel.

---

## Collecting State in Compose

**The naive way (Don't do this):**
```kotlin
val state by viewModel.uiState.collectAsState() 
// KEEPS collecting even when app is in background! 
// Wastes resources/battery.
```

**The safe way:**
```kotlin
// Needs: implementation("androidx.lifecycle:lifecycle-runtime-compose:...")
val state by viewModel.uiState.collectAsStateWithLifecycle()
```
- Automatically stops collecting when the Activity goes to `STOPPED`.
- Restarts when `STARTED`.

---

## Error Handling Strategies in MVVM

Where do we catch exceptions?

1.  **Repository?**
    - Returns `Result<T>`.
    - Pros: Explicit failures in signature.
    - Cons: Wrapper hell `Result<List<Result<Amiibo>>>`.

2.  **ViewModel? (Preferred)**
    - Repository throws exceptions (suspend functions).
    - ViewModel wraps calls in `try-catch`.
    - Maps `Exception` -> `UiState.Error`.

---

## Visualizing the Error Flow

```text
[Repository]
   |  fun getAmiibos() {
   |     throw IOException("No Internet")
   |  }
   v
[ViewModel]
   |  viewModelScope.launch {
   |     try {
   |        repo.getAmiibos()
   |     } catch (e: IOException) {
   |        _uiState.value = Error("Check Connection")
   |     }
   |  }
   v
[UI Layer]
   |  when(state) {
   |      is Error -> ShowSnackbar(state.message)
   |  }
```

---

<!-- _class: lead -->
# 2. Room Database
## Persistence & Offline-First

---

## Why Room?

**Room** is an abstraction layer over SQLite.

**Features:**
- Compile-time verification of SQL queries.
- Built-in migration support.
- Streamlined database access.
- **Observable queries** (Return `Flow<T>`).

It is the standard for localized persistence in Android.

---

## Entity Definition

Data classes marked with `@Entity`.

```kotlin
@Entity(tableName = "amiibos")
data class AmiiboEntity(
    @PrimaryKey
    val id: String, // String ID from API
    
    @ColumnInfo(name = "full_name") 
    val name: String,
    
    val gameSeries: String,
    
    // Room ignores this field
    @Ignore 
    val temporaryFlag: Boolean = false
)
```

---

## DAO (Data Access Object)

The interface to access the DB.

```kotlin
@Dao
interface AmiiboDao {
    
    // 1. Observable Read (Reactive)
    @Query("SELECT * FROM amiibos")
    fun getAll(): Flow<List<AmiiboEntity>>
    
    // 2. One-shot Write (Suspend)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<AmiiboEntity>)
    
    // 3. Transactions
    @Transaction
    suspend fun clearAndInsert(list: List<AmiiboEntity>) {
        deleteAll()
        insertAll(list)
    }
}
```

---

## Type Converters

SQLite only supports primitive types (Text, Int, Real, Blob). 
What if I want to save a `Date` or `List<String>`?

```kotlin
class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}
```
Register them in `@Database(entities = [...], version = 1)`.

---

## Database Migrations (The Horror)

When you change the schema (add a column), the app crashes on update if you don't migrate.

**Automated Migrations (New & Easy):**
```kotlin
@Database(
    version = 2,
    entities = [AmiiboEntity::class],
    autoMigrations = [
        AutoMigration (from = 1, to = 2)
    ]
)
abstract class AppDatabase : RoomDatabase()
```
Room calculates the diff and generates the SQL script.

---

## Manual Migrations

For complex changes (renaming columns, changing data types).

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE amiibos ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0"
        )
    }
}
```
Add to builder: `.addMigrations(MIGRATION_1_2)`

---

## Testing DAOs

DAOs should be tested with an in-memory database (fast, no file IO).

```kotlin
@Test
fun writeUserAndReadInList() = runTest {
    val amiibo = AmiiboEntity("1", "Mario")
    dao.insert(amiibo)
    
    val byName = dao.findByName("Mario")
    assertEquals(amiibo, byName)
}
```

---

## Offline-First Strategy

**Repository Logic:**
1.  **Always** emit data from Local DB first (`dao.getAll()`).
2.  Trigger a network refresh in the background.
3.  On network success, write to DB.
4.  DB emits new data automatically.

**Benefits:**
- App works immediately on launch.
- No loading spinners for cached content.
- Network errors don't wipe out the screen.

---

## Visualization: Offline Repo

```text
       [View] <---(Observe)--- [ViewModel] <---(Observe)---- [Repository]
                                                                 ^
                                                                 | 1. Query Flow
                                                                 |
                                                          [Room Database]
                                                                 ^
                                                                 | 3. Insert/Update
          [Network Refresh] -------------------------------------+
                 |
                 v
             [Retrofit] ---> [Internet]
```

---

<!-- _class: lead -->
# 3. Networking
## Retrofit, OkHttp & Coil

---

## Retrofit Essentials

Turns an HTTP API into a Kotlin interface.

```kotlin
interface ApiService {
    @GET("amiibo")
    suspend fun getAmiibos(
        @Query("type") type: String
    ): AmiiboResponse
}
```

**Key concept:** Retrofit uses **Dynamic Proxies** to generate the implementation code at runtime.

---

## JSON Parsing (Serialization)

**Kotlinx Serialization** is the modern standard (Kotlin-first, no reflection).

```kotlin
@Serializable
data class AmiiboDto(
    @SerialName("name") val characterName: String,
    val image: String
)
```

**vs Gson:**
- Gson is maintained by Google but "in maintenance mode".
- Gson uses reflection (slower).
- Kotlinx Serialization is typesafe (fails fast if non-nullable field is missing).

---

## OkHttp: The Engine

Retrofit is just a wrapper. **OkHttp** does the actual work.

**Interceptors:** Hooks into the request/response chain.

1.  **Logging Interceptor:** View JSON in Logcat.
2.  **Auth Interceptor:** Add `Authorization: Bearer xyz` header efficiently.
3.  **Cache Interceptor:** Force HTTP caching.

---

## Visualizing Interceptors

```text
    Request  --> [ Auth Interceptor ] --> (Add Header)
             --> [ Log  Interceptor ] --> (Print Request)
             --> [ Network Call     ] --> INTERNET
                                              |
    Response <-- [ Log  Interceptor ] <-- (Print Response)
             <-- [ ErrorInterceptor ] <-- (Catch 401/500)
             <-- Application
```

---

## Implementing a Header Interceptor

```kotlin
val authInterceptor = Interceptor { chain ->
    val originalRequest = chain.request()
    
    val newRequest = originalRequest.newBuilder()
        .header("Authorization", "Bearer $myToken")
        .header("User-Agent", "AmiiboApp/1.0")
        .build()
        
    chain.proceed(newRequest)
}
```
Plug it in: `OkHttpClient.Builder().addInterceptor(authInterceptor).build()`

---

## Coil: Coroutine Image Loader

Designed for Compose.

**Why Coil?**
- **Memory Caching**: Bitmaps are heavy. Coil reuses memory.
- **Disk Caching**: Saves downloads to local storage.
- **Downsampling**: Loads a small version of the image if the View is small.

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data("https://example.com/image.png")
        .crossfade(true)
        .build(),
    contentDescription = null
)
```

---

## Repository Pattern: Mapping

**The Boundary:**
- API returns `AmiiboDto` (Network Model).
- UI wants `Amiibo` (Domain Model).

**Why map?**
- Decouples backend changes from UI logic.
- Format dates, capitalize strings, filter nulls in the mapping layer.

```kotlin
fun AmiiboDto.toDomain(): Amiibo {
    return Amiibo(
        name = this.name.uppercase(),
        imageUrl = this.image ?: "placeholder.png"
    )
}
```

---

<!-- _class: lead -->
# 4. Deep Dive
## Architecture, Room & Network Internals

---

## Architecture: Compose Stability

Why does Compose sometimes recompose too much?

**Unstable Types:** `List`, `Var`, Classes from external modules without Stable marker.
**Stable Types:** `String`, `Int`, Data classes with `val` of Stable types.

**The Fix:**
Wrapper classes or `@Stable` annotation.

```kotlin
@Immutable
data class AmiiboListState(
    val items: List<Amiibo> // List is unstable interface, but @Immutable forces it
)
```
**Takeaway:** Always use `Immutable` / `Stable` for UI State to enable Skippability.

---

## Room: Invalidation Tracker

How does `Flow<List<User>>` update automatically?

1.  Room sets up a standard SQLite `Trigger` (or uses FileObserver in newer versions).
2.  When `INSERT/UPDATE` happens, the Trigger modifies a tracking table `room_table_modification_log`.
3.  Room's `InvalidationTracker` observes this.
4.  If the table you are querying changed, it re-runs the SELECT query.

**Performance Note:** It re-runs the **ENTIRE** query. It receives a new List. Compose creates new items.

---

## Network: Connection Pooling (OkHttp)

Why is the first request slow and subsequent ones fast?

**TCP Handshake (SYN, SYN-ACK, ACK) + TLS Handshake** takes time.

**Connection Pooling:**
OkHttp keeps the socket open for ~5 mins (Keep-Alive).
If you request the same host again, it reuses the socket.

**GZIP Compression:**
OkHttp automatically adds `Accept-Encoding: gzip`.
It transparently unzips the response body.
This can reduce payload size by 70-90%.

---

<!-- _class: lead -->
# 5. Challenge Lab
## Practice & Application

---

## Intro: The "Amiibo Vault" App

We have a skeleton app that:
1.  Fetches Amiibos from API.
2.  Stores them in Room.
3.  Displays them in a Grid.

**The functionality is basic.** We need to make it robust and feature-rich.

---

## Challenge: Refactor Error Handling

**Goal:** Provide a seamless Offline Experience.

**Current Behavior:** 
- If API fails, `refreshAmiibos` catches exception and sets `UiState.Error`.
- UI shows a red Error Screen.
- **Problem:** If I have data in Room, I shouldn't see an Error Screen!

**Task:**
1.  Modify `UiState.Error` to accept `data: List<Amiibo>?`.
2.  In ViewModel, emit `Error` but pass the current `_uiState.value.data`.
3.  In UI, if state is Error but has data -> Show a `Snackbar` ("Offline Mode") but keep the Grid visible.

---

## Challenge: Search Feature (New)

**Goal:** Allow users to filter Amiibos locally without hitting the API.

**Steps:**

1.  **DAO Update:**
    - Add a new query method that filters by name.
    - `@Query("SELECT * FROM amiibos WHERE name LIKE '%' || :query || '%'")`
    - `fun searchAmiibos(query: String): Flow<List<AmiiboEntity>>`

2.  **ViewModel Update:**
    - Add a `MutableStateFlow<String>` for the search query.
    - Use `flatMapLatest` (advanced) or `combine` to switch between the full list flow and the search flow based on user input.

---

## Search Logic (ViewModel)

```kotlin
private val _searchQuery = MutableStateFlow("")
val searchQuery = _searchQuery.asStateFlow()

@OptIn(ExperimentalCoroutinesApi::class)
val uiState = _searchQuery
    .flatMapLatest { query ->
        if (query.isBlank()) {
            repository.getAll()
        } else {
            repository.search(query)
        }
    }
    .map { AmiiboUiState.Success(it) }
    .stateIn(viewModelScope, ...)
```

---

<!-- _class: lead -->
# Resources & Wrap-up

---

## Resources

- [Android Architecture Guide](https://developer.android.com/topic/architecture)
- [Room Training](https://developer.android.com/training/data-storage/room)
- [Coroutines & Flow Best Practices](https://medium.com/androiddevelopers)
- [Koin Documentation](https://insert-koin.io/)
- [Amiibo API](https://www.amiiboapi.com/)
