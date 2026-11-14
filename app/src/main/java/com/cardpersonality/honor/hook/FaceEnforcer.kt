package com.cardpersonality.honor.hook

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Display-layer enforcement for card-face ImageViews.
 *
 * Wallet loads faces asynchronously (Glide) and may overwrite our bitmap at any time.
 * For every registered view we install an OnPreDrawListener: right before a frame is drawn,
 * if the view does not already show our bitmap, we set it (beats late Glide callbacks).
 * Listeners are removed when the view is detached.
 *
 * Also provides [ourDrawableFor] so the ImageView.setImageDrawable guard can redirect any
 * third-party write on a registered view to our bitmap.
 */
object FaceEnforcer {
    private val targets = java.util.Collections.synchronizedMap(WeakHashMap<ImageView, Target>())
    private val listeners = java.util.Collections.synchronizedMap(WeakHashMap<ImageView, ViewTreeObserver.OnPreDrawListener>())

    private class Target(val face: ProviderClient.ResolvedFace)

    fun register(view: ImageView, face: ProviderClient.ResolvedFace) {
        val previous = targets.put(view, Target(face))
        if (previous == null) ensureListener(view)
        applyIfReady(view)
    }

    fun unregister(view: ImageView) {
        targets.remove(view)
        listeners.remove(view)?.let { listener ->
            try {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
            } catch (_: Throwable) {
            }
        }
    }

    private fun targetOf(view: ImageView): Target? = targets[view]

    /** Applies our bitmap immediately when it is already decoded. */
    fun applyIfReady(view: ImageView): Boolean {
        val target = targetOf(view) ?: return false
        val bitmap = FaceImageLoader.peek(target.face) ?: return false
        return applyBitmap(view, bitmap)
    }

    /** Applies [bitmap] to [view] (no-op when recycled/empty). Returns true on success. */
    fun applyBitmap(view: ImageView, bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0) return false
        val effective = FaceImageLoader.roundedFor(view, bitmap)
        return try {
            val existing = view.drawable
            if (existing is BitmapDrawable && existing.bitmap === effective) {
                // Already showing our face; only make sure the app's tint is gone.
                if (view.colorFilter != null) view.colorFilter = null
                return true
            }
            view.setImageBitmap(effective)
            // The app may tint the face for locked/invalid states; our face must stay as composed.
            view.colorFilter = null
            true
        } catch (t: Throwable) {
            HLog.w("applyBitmap failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Guard hook support: the drawable we want on [view] right now, or null when
     * the view is not registered / our bitmap is not decoded yet.
     */
    fun ourDrawableFor(view: ImageView): Drawable? {
        val target = targetOf(view) ?: return null
        val bitmap = FaceImageLoader.peek(target.face) ?: return null
        val effective = FaceImageLoader.roundedFor(view, bitmap)
        val existing = view.drawable
        if (existing is BitmapDrawable && existing.bitmap === effective) return null // already ours
        return try {
            BitmapDrawable(view.resources, effective)
        } catch (_: Throwable) {
            null
        }
    }

    fun isRegistered(view: ImageView): Boolean = targets.containsKey(view)

    private fun ensureListener(view: ImageView) {
        try {
            if (!view.isAttachedToWindow) {
                // Attach later via re-register on bind; still schedule a one-shot.
                view.post {
                    if (view.isAttachedToWindow) ensureListener(view)
                }
                return
            }
            val existing = listeners[view]
            if (existing != null) {
                return
            }
            val listener = ViewTreeObserver.OnPreDrawListener {
                try {
                    if (!view.isAttachedToWindow) {
                        unregister(view)
                        true
                    } else {
                        val target = targetOf(view)
                        if (target == null) {
                            unregister(view)
                        } else {
                            applyIfReady(view)
                        }
                        true
                    }
                } catch (t: Throwable) {
                    Log.w(HLog.TAG, "preDraw enforcer error", t)
                    true
                }
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            listeners[view] = listener
        } catch (t: Throwable) {
            HLog.w("ensureListener failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Snapshot of registered views for CONFIG_CHANGED refresh (weak keys). */
    fun registeredViews(): List<Pair<ImageView, ProviderClient.ResolvedFace>> {
        val out = ArrayList<Pair<ImageView, ProviderClient.ResolvedFace>>()
        synchronized(targets) {
            for ((view, target) in targets) {
                if (view != null) out.add(view to target.face)
            }
        }
        return out
    }

    /** All currently registered views (diagnostics). */
    fun registeredCount(): Int = targets.size
}
