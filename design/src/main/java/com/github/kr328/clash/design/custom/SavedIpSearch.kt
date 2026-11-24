package com.github.kr328.clash.design.custom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager

// changes.md #175: search UI state, debounce, and expansion behavior for saved IPs.
internal class SavedIpSearch(private val env: SavedIpEnv) {
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearchRender: Runnable? = null
    var renderedQuery: String? = null

fun scheduleSearchRender() {
        cancelPendingSearchRender()
        val task = Runnable {
            pendingSearchRender = null
            env.renderRows()
        }
        pendingSearchRender = task
        searchHandler.postDelayed(task, 300L)
    }

fun cancelPendingSearchRender() {
        pendingSearchRender?.let(searchHandler::removeCallbacks)
        pendingSearchRender = null
    }

fun expandSearch() {
        env.filterState.searchExpanded = true
        updateSearchUi()
        env.searchInput.requestFocus()
        env.searchInput.post {
            (env.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(env.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

fun collapseSearch() {
        if (!env.filterState.searchExpanded) return
        (env.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(env.searchInput.windowToken, 0)
        env.filterState.searchExpanded = false
        env.searchInput.clearFocus()
        updateSearchUi()
        env.renderRows()
    }

fun updateSearchUi() {
        val expanded = env.filterState.searchExpanded
        env.searchIconButton.visibility = if (expanded) View.GONE else View.VISIBLE
        env.searchBox.visibility = if (expanded) View.VISIBLE else View.GONE
        env.searchCloseButton.visibility = if (expanded) View.VISIBLE else View.GONE
        updateSearchIndicator()
    }

fun updateSearchIndicator() {
        val query = env.searchInput.text?.toString().orEmpty()
        env.searchIndicator.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
        env.searchClearButton.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
