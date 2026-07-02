package dev.jdtech.jellyfin.setup.data

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ExceptionUiText
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import timber.log.Timber

class SetupRepositoryImpl(
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : SetupRepository {
    override fun discoverServers(): Flow<ServerDiscoveryInfo> {
        return jellyfinApi.jellyfin.discovery.discoverLocalServers()
    }

    override suspend fun getServers(): List<ServerWithAddresses> {
        return database.getServersWithAddresses()
    }

    override suspend fun getCurrentServer(): Server? {
        return appPreferences.getValue(appPreferences.currentServer)?.let { id -> database.get(id) }
    }

    override suspend fun deleteServer(serverId: String) {
        database.delete(serverId)
    }

    override suspend fun getIsQuickConnectEnabled(): Boolean =
        withContext(Dispatchers.IO) { jellyfinApi.quickConnectApi.getQuickConnectEnabled().content }

    override suspend fun initiateQuickConnect(): QuickConnectResult =
        withContext(Dispatchers.IO) { jellyfinApi.quickConnectApi.initiateQuickConnect().content }

    override suspend fun getQuickConnectState(secret: String): QuickConnectResult =
        withContext(Dispatchers.IO) {
            jellyfinApi.quickConnectApi.getQuickConnectState(secret).content
        }

    override suspend fun setCurrentServer(serverId: String) {
        val serverWithAddressAndUser = database.getServerWithAddressAndUser(serverId) ?: return
        val serverAddress = serverWithAddressAndUser.address ?: return
        val user = serverWithAddressAndUser.user

        jellyfinApi.apply {
            api.update(baseUrl = serverAddress.address, accessToken = user?.accessToken)
            userId = user?.id
        }
    }

    override suspend fun addServer(address: String): Server {
        if (address.isBlank()) {
            throw ExceptionUiText(UiText.DynamicString("Address cannot be empty"))
        }

        val candidates = jellyfinApi.jellyfin.discovery.getAddressCandidates(address)
        val recommended =
            jellyfinApi.jellyfin.discovery.getRecommendedServers(
                candidates,
                RecommendedServerInfoScore.OK,
            )
        val goodServers = mutableListOf<RecommendedServerInfo>()
        val okServers = mutableListOf<RecommendedServerInfo>()

        for (recommendedServerInfo in recommended) {
            when (recommendedServerInfo.score) {
                RecommendedServerInfoScore.GREAT -> {
                    return saveServerInDatabase(recommendedServerInfo)
                }
                RecommendedServerInfoScore.GOOD -> goodServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.OK -> okServers.add(recommendedServerInfo)
                RecommendedServerInfoScore.BAD -> Unit
            }
        }

        when {
            goodServers.isNotEmpty() -> {
                return saveServerInDatabase(goodServers.first())
            }
            okServers.isNotEmpty() -> {
                val okServer = okServers.first()
                throw ExceptionUiText(createIssuesString(okServer).first())
            }
            else -> {
                throw ExceptionUiText(UiText.DynamicString("Server not found"))
            }
        }
    }

    private fun saveServerInDatabase(recommendedServerInfo: RecommendedServerInfo): Server {
        val serverInfo =
            recommendedServerInfo.systemInfo.getOrNull()
                ?: throw ExceptionUiText(UiText.DynamicString("Server ID not found"))

        val serverIdValue = serverInfo.id ?: throw ExceptionUiText(
            UiText.DynamicString("Server ID not found")
        )

        Timber.d("Connecting to server: ${serverInfo.serverName}")

        val serverInDatabase = database.get(serverIdValue)

        val server =
            if (serverInDatabase != null) {
                val addresses = database.getServerWithAddresses(serverInDatabase.id).addresses
                if (addresses.none { it.address == recommendedServerInfo.address }) {
                    val serverAddress =
                        ServerAddress(
                            id = UUID.randomUUID(),
                            serverId = serverInDatabase.id,
                            address = recommendedServerInfo.address,
                        )
                    database.insertServerAddress(serverAddress)
                }
                serverInDatabase
            } else {
                val serverAddress =
                    ServerAddress(
                        id = UUID.randomUUID(),
                        serverId = serverIdValue,
                        address = recommendedServerInfo.address,
                    )

                val newServer =
                    Server(
                        id = serverIdValue,
                        name = serverInfo.serverName ?: throw ExceptionUiText(
                            UiText.DynamicString("Server name not found")
                        ),
                        currentServerAddressId = serverAddress.id,
                        currentUserId = null,
                    )

                database.insertServer(newServer)
                database.insertServerAddress(serverAddress)
                newServer
            }

        jellyfinApi.apply {
            api.update(baseUrl = recommendedServerInfo.address, accessToken = null)
        }

        return server
    }

    private fun createIssuesString(server: RecommendedServerInfo): Collection<UiText> {
        return server.issues.map {
            when (it) {
                is RecommendedServerIssue.OutdatedServerVersion -> {
                    UiText.DynamicString("Outdated server version: ${it.version}")
                }
                is RecommendedServerIssue.InvalidProductName -> {
                    UiText.DynamicString("Invalid product name: ${it.productName ?: ""}")
                }
                is RecommendedServerIssue.UnsupportedServerVersion -> {
                    UiText.DynamicString("Unsupported server version: ${it.version}")
                }
                is RecommendedServerIssue.SlowResponse -> {
                    UiText.DynamicString("Slow response from server")
                }
                is RecommendedServerIssue.ServerUnreachable -> {
                    UiText.DynamicString("Server unreachable")
                }
                else -> UiText.DynamicString("Unknown issue")
            }
        }
    }

    // Changed to private, as the interface now uses `login`
    private suspend fun authenticateUser(
        serverId: String,
        username: String,
        password: String,
    ): User {
        val serverWithAddressAndUser = database.getServerWithAddressAndUser(serverId) ?: throw ExceptionUiText(
            UiText.DynamicString("Server not found")
        )
        val serverAddress = serverWithAddressAndUser.address ?: throw ExceptionUiText(
            UiText.DynamicString("Server address not found")
        )

        jellyfinApi.apply {
            api.update(baseUrl = serverAddress.address, accessToken = null)
        }

        val authenticationResult =
            withContext(Dispatchers.IO) {
                jellyfinApi.userApi.authenticateUserByName(
                    data = AuthenticateUserByName(
                        username = username,
                        pw = password,
                    ),
                ).content
            }

        saveAuthenticationResult(authenticationResult)

        return database.getUser(authenticationResult.user?.id ?: throw ExceptionUiText(
            UiText.DynamicString("No user found")
        )) ?: throw ExceptionUiText(
            UiText.DynamicString("User not found in database")
        )
    }

    private fun saveAuthenticationResult(authenticationResult: AuthenticationResult) {
        val currentUserId = authenticationResult.user?.id ?: throw ExceptionUiText(
            UiText.DynamicString("No user found")
        )
        val userName = authenticationResult.user?.name ?: ""
        val serverId = authenticationResult.serverId ?: throw ExceptionUiText(
            UiText.DynamicString("No server found")
        )
        val accessToken = authenticationResult.accessToken ?: throw ExceptionUiText(
            UiText.DynamicString("No access token found")
        )

        val user =
            User(
                id = currentUserId,
                name = userName,
                serverId = serverId,
                accessToken = accessToken,
            )

        database.insertUser(user)
        database.updateServerCurrentUser(serverId, user.id)

        jellyfinApi.apply {
            api.update(accessToken = accessToken)
            userId = user.id
        }
    }

    override suspend fun getUsers(serverId: String): List<User> {
        return database.getUsers(serverId)
    }

    override suspend fun getPublicUsers(serverId: String): List<User> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userApi.getPublicUsers().content.map { userDto ->
                User(
                    id = userDto.id,
                    name = userDto.name ?: "",
                    serverId = serverId,
                    accessToken = null,
                )
            }
        }

    override suspend fun loadDisclaimer(): String? = null

    override suspend fun deleteUser(userId: UUID) {
        database.deleteUser(userId)
    }

    // --- Missing Interface Implementations ---
    override suspend fun login(username: String, password: String) {
        val serverId = appPreferences.getValue(appPreferences.currentServer)
            ?: throw ExceptionUiText(UiText.DynamicString("No server selected"))
        authenticateUser(serverId, username, password)
    }

    override suspend fun loginWithSecret(secret: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getCurrentUser(): User? {
        return appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
            database.getServerWithAddressAndUser(serverId)?.user
        }
    }

    override suspend fun setCurrentUser(userId: UUID) {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
        database.updateServerCurrentUser(serverId, userId)
    }

    override suspend fun setCurrentAddress(addressId: UUID) {
        TODO("Not yet implemented")
    }
}