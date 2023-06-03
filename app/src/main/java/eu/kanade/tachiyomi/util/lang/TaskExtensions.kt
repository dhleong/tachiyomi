package eu.kanade.tachiyomi.util.lang

import android.util.Log
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <TResult> Task<TResult>.await(): TResult {
    return suspendCancellableCoroutine { continuation ->
        Log.v("ml", "TASK=$this / ${this.javaClass}")
        continuation.invokeOnCancellation {
            // Is it possible to cancel the task?
        }
        addOnCanceledListener { continuation.cancel() }
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { e -> continuation.resumeWithException(e) }
    }
}
