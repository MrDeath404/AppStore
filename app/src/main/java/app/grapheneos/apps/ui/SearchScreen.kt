package app.grapheneos.apps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import app.grapheneos.apps.core.PackageSource
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.databinding.SearchScreenBinding
import app.grapheneos.apps.util.requestKeyboard
import java.util.EnumSet
import com.google.android.material.chip.Chip

class SearchScreen : PackageListFragment<SearchScreenBinding>() {
    private val model by viewModels<SearchScreenVM>()

    private lateinit var srcFilters: Map<Chip, PackageSource>

    private fun hasPackagesForSource(source: PackageSource): Boolean {
        return packages.values.any { it.rPackage.source == source }
    }

    class SearchScreenVM : ViewModel() {
        var searchQuery = MutableLiveData("")

        private val selectedSourcesSet: EnumSet<PackageSource> = EnumSet.noneOf(PackageSource::class.java)

        val selectedSources = MutableLiveData(selectedSourcesSet)

        fun modifyFilter(filter: PackageSource, isChecked: Boolean) {
            if (isChecked) selectedSourcesSet.add(filter) else selectedSourcesSet.remove(filter)
            selectedSources.value = selectedSourcesSet
        }
    }

    override fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean) =
        SearchScreenBinding.inflate(inflater, container, attach)

    private var viewCreatedAtLeastOnce = false

    override fun onViewsCreated(views: SearchScreenBinding, savedInstanceState: Bundle?) {
        createListAdapter(views.appList)

        views.searchInput.let { editText ->
            if (savedInstanceState == null && !viewCreatedAtLeastOnce) {
                // com.google.android.material.textfield.TextInputLayout sometimes gets into an
                // inconsistent state if keyboard is requested immediately
                mainHandler.postDelayed(300L) {
                    requestKeyboard(editText)
                }
            }
            editText.doAfterTextChanged {
                model.searchQuery.value = it?.toString()?.trimStart() ?: ""
            }
        }

        viewCreatedAtLeastOnce = true

        model.searchQuery.observe(viewLifecycleOwner) {
            updateList()
        }

        srcFilters = mapOf(
            views.pkgSourceGrapheneOS to PackageSource.GrapheneOS,
            views.pkgSourceLineageos to PackageSource.LineageOS,
            views.pkgSourceMirror to PackageSource.Mirror,
            views.pkgSourceGoogle to PackageSource.Google,
            views.pkgSourceGrapheneOSBuild to PackageSource.GrapheneOS_build,
        )

        model.selectedSources.observe(viewLifecycleOwner) { filters ->
            srcFilters.forEach {
                it.key.isChecked = filters.contains(it.value)
            }

            updateList()
        }

        srcFilters.forEach {
            it.key.setOnCheckedChangeListener { _, isChecked ->
                model.modifyFilter(it.value, isChecked)
            }
        }
    }

    override fun updateList() {
        srcFilters.forEach { (chip, source) ->
            chip.isVisible = hasPackagesForSource(source)
        }
        val selectedSources = model.selectedSources.value!!
        val query = model.searchQuery.value!!
        val list = packages.values.filter {
            val pkg = it.rPackage
            var res = false
            if (pkg.common.isTopLevel) {
                if (query.isEmpty()) {
                    res = selectedSources.contains(pkg.source)
                } else if (pkg.label.contains(query, ignoreCase = true)) {
                    res = selectedSources.isEmpty() || selectedSources.contains(pkg.source)
                }
            }
            res
        }.sortedWith { a, b ->
            val pkg1 = a.rPackage
            val pkg2 = b.rPackage
            var res = pkg1.source.compareTo(pkg2.source)
            if (res == 0) {
                res = pkg1.label.compareTo(pkg2.label)
            }
            res
        }

        listAdapter.updateList(list)
    }
}
