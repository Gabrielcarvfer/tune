package com.music.tune.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Runs a slow [prepare] step for many items, [parallel] at a time, and hands
 * each result to [finish] as soon as it's ready, in whatever order they
 * complete (each result comes with its item). Used to fingerprint songs on
 * several cores while their rate-limited lookups go out one after another.
 *
 * Errors from [prepare] are passed to [finish]; an exception thrown by
 * [finish] cancels the remaining work and is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T, P> pipeline(
    items: List<T>,
    parallel: Int,
    dispatcher: CoroutineDispatcher,
    prepare: suspend (T) -> P,
    finish: suspend (T, Result<P>) -> Unit,
) {
    items.asFlow()
        .flatMapMerge(concurrency = parallel.coerceAtLeast(1)) { item ->
            flow {
                val result = try {
                    Result.success(prepare(item))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                emit(item to result)
            }.flowOn(dispatcher)
        }
        .collect { (item, result) -> finish(item, result) }
}
