package com.luminapaint.draw.ads

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.luminapaint.draw.BuildConfig

class AdManager(private val context: Context) {
    companion object {
        private const val TAG = "LuminaAdManager"
        private const val INTERSTITIAL_COOLDOWN_MS = 60_000L
    }

    private var bannerAdView: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    private var isLoadingInterstitial = false
    private var lastInterstitialShownMs = 0L

    fun loadAdaptiveBanner(activity: Activity, container: FrameLayout) {
        container.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            runCatching {
                val adSize = computeAdaptiveBannerSize(activity, container)
                val adView = AdView(activity).apply {
                    adUnitId = BuildConfig.ADMOB_BANNER_ID
                    setAdSize(adSize)
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            container.visibility = View.VISIBLE
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            Log.w(TAG, "Banner failed to load: ${error.message}")
                        }
                    }
                }
                bannerAdView?.destroy()
                bannerAdView = adView
                container.removeAllViews()
                container.addView(
                    adView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                adView.loadAd(AdRequest.Builder().build())
            }.onFailure {
                Log.w(TAG, "Unable to initialize banner ad", it)
            }
        }
    }

    private fun computeAdaptiveBannerSize(activity: Activity, container: View): AdSize {
        val density = activity.resources.displayMetrics.density
        val widthPixels = if (container.width > 0) {
            container.width
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.width()
        } else {
            activity.resources.displayMetrics.widthPixels
        }
        val adWidthDp = (widthPixels / density).toInt().coerceAtLeast(320)
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp)
    }

    fun preloadInterstitial() {
        if (interstitialAd != null || isLoadingInterstitial) return
        isLoadingInterstitial = true
        runCatching {
            InterstitialAd.load(
                context.applicationContext,
                BuildConfig.ADMOB_INTERSTITIAL_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        isLoadingInterstitial = false
                        interstitialAd = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        isLoadingInterstitial = false
                        interstitialAd = null
                        Log.w(TAG, "Interstitial failed to load: ${error.message}")
                    }
                }
            )
        }.onFailure {
            isLoadingInterstitial = false
            Log.w(TAG, "Unable to request interstitial ad", it)
        }
    }

    fun showInterstitialIfReady(activity: Activity, onComplete: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        val ad = interstitialAd
        if (ad == null || now - lastInterstitialShownMs < INTERSTITIAL_COOLDOWN_MS ||
            activity.isFinishing || activity.isDestroyed
        ) {
            if (ad == null) preloadInterstitial()
            onComplete()
            return
        }

        interstitialAd = null
        lastInterstitialShownMs = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadInterstitial()
                onComplete()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial failed to show: ${error.message}")
                preloadInterstitial()
                onComplete()
            }
        }
        ad.show(activity)
    }

    fun resumeBanner() {
        bannerAdView?.resume()
    }

    fun pauseBanner() {
        bannerAdView?.pause()
    }

    fun destroy() {
        bannerAdView?.destroy()
        bannerAdView = null
        interstitialAd = null
    }
}
