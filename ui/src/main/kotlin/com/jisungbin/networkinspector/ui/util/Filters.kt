package com.jisungbin.networkinspector.ui.util

import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.ui.StatusFilter

fun List<NetworkRow>.applyFilters(
    search: String,
    statusFilter: StatusFilter,
    methodFilter: String?,
): List<NetworkRow> = asSequence()
    .filter { row ->
        if (search.isBlank()) return@filter true
        row.url.contains(search, ignoreCase = true) || row.method.contains(search, ignoreCase = true)
    }
    .filter { row ->
        when (statusFilter) {
            StatusFilter.All -> true
            StatusFilter.InFlight -> row.state == ConnectionState.IN_FLIGHT
            StatusFilter.Success -> row.statusCode in 200..299
            StatusFilter.Redirect -> row.statusCode in 300..399
            StatusFilter.ClientError -> row.statusCode in 400..499
            StatusFilter.ServerError -> row.statusCode in 500..599
        }
    }
    .filter { row ->
        methodFilter == null || row.method.equals(methodFilter, ignoreCase = true)
    }
    .toList()
