# Torus Session Manager Android

[![](https://jitpack.io/v/com.github.web3auth/session-manager-android.svg)](https://jitpack.io/#com.github.web3auth/session-manager-android)

> Web3Auth is where passwordless auth meets non-custodial key infrastructure for Web3 apps and wallets. By aggregating OAuth (Google, Twitter, Discord) logins, different wallets and innovative Multi Party Computation (MPC) - Web3Auth provides a seamless login experience to every user on your application.

Torus Session Manager Android creates, authorizes, updates, and invalidates encrypted sessions, and manages access/refresh tokens with authenticated HTTP. It provides two modules:

1. **Storage Manager** — legacy encrypted metadata storage with a server-backed hex session ID.
2. **Auth Session Manager** — token-based sessions with automatic refresh and an authenticated HTTP client.

## Features

- Multi network support
- All public APIs return `CompletableFuture`s
- Configurable session server URL (required; no silent fallback)
- Optional EncryptedSharedPreferences local cache for StorageManager
- Auth token lifecycle with refresh deduplication and 401 retry

## Getting Started

This project uses [jitpack](https://jitpack.io/docs/) for release management.

Add the relevant dependency to your project:

```groovy
repositories {
  maven { url "https://jitpack.io" }
}
dependencies {
  implementation 'com.github.web3auth:session-manager-android:5.0.0'
}
```

### Permissions

Open your app's `AndroidManifest.xml` file and add the following permission:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Requirements

- Android API version 26 or newer is required.

## Storage Manager

`StorageManager` creates, authorizes, updates, and invalidates server-backed sessions using a hex session ID.

`sessionServerBaseUrl` is **required**. Use `SESSION_SERVER_API_URL` (`https://api.web3auth.io/session-service`) unless you host your own session service.

```kotlin
import com.web3auth.session_manager_android.StorageManager
import com.web3auth.session_manager_android.interfaces.SESSION_SERVER_API_URL

val storage = StorageManager(
    context = this,
    sessionServerBaseUrl = SESSION_SERVER_API_URL,
    sessionTime = 86400,
    allowedOrigin = packageName,
    sessionId = StorageManager.generateRandomSessionKey(),
    sessionNamespace = "my-app",
    useLocalStorage = true
)

storage.createSession("""{"userId":"123"}""", this).get()
val sessionData = storage.authorizeSession(packageName, this).get()
storage.updateSession("""{"userId":"123","role":"admin"}""", this).get()
storage.invalidateSession(this).get()
```

## Auth Session Manager

`AuthSessionManager` handles access tokens, refresh tokens, and encrypted session data. `HttpClient` attaches a Bearer token and retries once on 401 with a deduplicated refresh.

```kotlin
import com.web3auth.session_manager_android.auth.ApiClientConfig
import com.web3auth.session_manager_android.auth.AuthSessionManager
import com.web3auth.session_manager_android.auth.AuthTokens
import com.web3auth.session_manager_android.auth.HttpClient
import com.web3auth.session_manager_android.auth.HttpClientRequestOptions

val session = AuthSessionManager(
    context = this,
    apiClientConfig = ApiClientConfig(
        baseURL = "https://auth.example.com",
        sessionsEndpoint = "/v1/auth/session",
        logoutEndpoint = "/v1/auth/logout"
    )
)

session.setTokensAsync(
    AuthTokens(
        sessionId = "...",
        accessToken = "...",
        refreshToken = "...",
        idToken = "..."
    )
).get()

val sessionData = session.authorizeAsync().get()
val http = HttpClient(session)
val users = http.get(
    "https://api.example.com/users",
    HttpClientRequestOptions(authenticated = true)
).get()

session.logoutAsync().get()
```

By default, session ID / access token / ID token and the refresh token are stored in separate `EncryptedSharedPreferences` files. Pass `StorageConfig` to override adapters (`MemoryStorageAdapter` is useful in tests).

## Migration from SessionManager (v3)

| v3 | v5 |
| --- | --- |
| `SessionManager` | `StorageManager` |
| Hardcoded `https://session.web3auth.io` | Required `sessionServerBaseUrl` |
| `createSession` / `authorizeSession` / `invalidateSession` | Same, plus `updateSession`, `clearStorage`, `clearOrphanedData` |
| Public mutable session id | `setSessionId()` validates hex, pads to 32 bytes, prefixes `0x` |
| — | `AuthSessionManager` + `HttpClient` |

`SessionManager` remains as a `@Deprecated` subclass of `StorageManager` and also requires `sessionServerBaseUrl`.

Default session timeout is **86400 seconds** (1 day), matching web v5. The previous Android default was 30 days.

`generateRandomSessionKey()` now returns a `0x`-prefixed 64-character hex string.

## 🩹 Examples

Checkout the examples for your preferred blockchain and platform in
our [example repository](https://github.com/Web3Auth/session-manager-android/tree/master/app)

## 💬 Troubleshooting and Discussions

- Have a look at
  our [GitHub Discussions](https://github.com/Web3Auth/Web3Auth/discussions?discussions_q=sort%3Atop)
  to see if anyone has any questions or issues you might be having.
- Checkout our [Troubleshooting Documentation Page](https://web3auth.io/docs/troubleshooting) to
  know the common issues and solutions
- Join our [Discord](https://discord.gg/web3auth) to join our community and get private integration
  support or help with your integration.
