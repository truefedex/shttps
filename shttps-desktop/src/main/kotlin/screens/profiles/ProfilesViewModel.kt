package com.phlox.simpleserver.screens.profiles

import androidx.lifecycle.ViewModel
import com.phlox.simpleserver.profiles.ConfigProfile
import com.phlox.simpleserver.profiles.ConfigProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One row of the profiles list: the registry entry plus what is only true on disk. */
data class ProfileRow(
    val profile: ConfigProfile,
    val active: Boolean,
    /** The directory this profile owns, which is where everything of its own lives. */
    val directory: String,
    val canDelete: Boolean,
    /** Whether "delete its data too" may even be offered - never for the legacy profile. */
    val ownsDeletableData: Boolean,
)

data class ProfilesState(
    val rows: List<ProfileRow> = emptyList(),
)

/**
 * The profiles list. Switching is deliberately not done here: it replaces the live `SHTTPSApp`,
 * which `HomeViewModel` owns, so the screen calls `HomeViewModel.switchProfile` and this view
 * model only reads and edits the registry.
 */
class ProfilesViewModel(
    private val profiles: ConfigProfiles,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfilesState())
    val uiState: StateFlow<ProfilesState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val activeId = profiles.activeProfileId
        _uiState.value = ProfilesState(
            rows = profiles.all().map { profile ->
                ProfileRow(
                    profile = profile,
                    active = profile.id == activeId,
                    directory = profiles.profileDir(profile.id).absolutePath,
                    canDelete = profiles.canDelete(profile.id),
                    ownsDeletableData = profiles.ownsDeletableDataDir(profile.id),
                )
            }
        )
    }

    /** @return false when the directories could not be created, in which case nothing was added */
    fun createProfile(name: String): Boolean {
        val created = profiles.createProfile(name) != null
        if (created) refresh()
        return created
    }

    fun renameProfile(profileId: String, name: String) {
        if (profiles.renameProfile(profileId, name)) refresh()
    }

    fun deleteProfile(profileId: String, deleteData: Boolean) {
        if (profiles.deleteProfile(profileId, deleteData)) refresh()
    }
}
