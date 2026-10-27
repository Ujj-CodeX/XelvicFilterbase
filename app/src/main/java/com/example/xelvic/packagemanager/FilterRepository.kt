package com.example.xelvic.packagemanager

import android.content.Context
import android.net.Uri
import com.example.xelvic.model.InstalledFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for available filter packages and currently selected filter.
 */
class FilterRepository(private val context: Context) {

    private val packageManager = FilterPackageManager(context)

    private val _installedFilters = MutableStateFlow<List<InstalledFilter>>(emptyList())
    val installedFilters: StateFlow<List<InstalledFilter>> = _installedFilters.asStateFlow()

    private val _selectedFilter = MutableStateFlow<InstalledFilter?>(null)
    val selectedFilter: StateFlow<InstalledFilter?> = _selectedFilter.asStateFlow()

    init {
        reloadInstalledFilters()
    }

    /**
     * Initializes bundled filters if needed and reloads the list from storage.
     */
    fun reloadInstalledFilters() {
        packageManager.installBundledFiltersIfNecessary()
        val list = packageManager.listInstalledFilters()
        _installedFilters.value = list

        // If no filter selected yet, select the first installed filter (e.g. Butterfly) by default
        if (_selectedFilter.value == null && list.isNotEmpty()) {
            _selectedFilter.value = list.first()
        } else if (_selectedFilter.value != null) {
            // Update reference if still present
            val currentId = _selectedFilter.value!!.id
            val updated = list.firstOrNull { it.id == currentId }
            _selectedFilter.value = updated
        }
    }

    fun selectFilter(filter: InstalledFilter?) {
        _selectedFilter.value = filter
    }

    fun importFilter(uri: Uri): Result<InstalledFilter> {
        return try {
            val installed = packageManager.installFromUri(uri)
            reloadInstalledFilters()
            _selectedFilter.value = installed
            Result.success(installed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteFilter(filterId: String): Boolean {
        val success = packageManager.removeFilter(filterId)
        if (success) {
            if (_selectedFilter.value?.id == filterId) {
                _selectedFilter.value = null
            }
            reloadInstalledFilters()
        }
        return success
    }
}
