/*
 * Copyright (C) 2019. Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.sweers.catchup.ui.about

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.text.style.StyleSpan
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Px
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.text.layoutDirection
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.uber.autodispose.autoDispose
import dagger.Module
import dagger.android.ContributesAndroidInjector
import io.noties.markwon.Markwon
import io.sweers.catchup.R
import io.sweers.catchup.base.ui.InjectingBaseActivity
import io.sweers.catchup.base.ui.InjectingBaseFragment
import io.sweers.catchup.base.ui.VersionInfo
import io.sweers.catchup.base.ui.versionInfo
import io.sweers.catchup.data.LinkManager
import io.sweers.catchup.injection.ActivityModule
import io.sweers.catchup.injection.scopes.PerFragment
import io.sweers.catchup.service.api.UrlMeta
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.util.LinkTouchMovementMethod
import io.sweers.catchup.util.TouchableUrlSpan
import io.sweers.catchup.util.UiUtil
import io.sweers.catchup.util.buildMarkdown
import io.sweers.catchup.util.customtabs.CustomTabActivityHelper
import io.sweers.catchup.util.isInNightMode
import io.sweers.catchup.util.kotlin.windowed
import io.sweers.catchup.util.parseMarkdownAndPlainLinks
import io.sweers.catchup.util.setLightStatusBar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotterknife.bindView
import ru.ldralighieri.corbind.material.offsetChanges
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

class AboutActivity : InjectingBaseActivity() {

  @Inject
  internal lateinit var customTab: CustomTabActivityHelper

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

//    lifecycle.addObserver(object : LifecycleObserver {
//      @OnLifecycleEvent(Lifecycle.Event.ON_START)
//      fun start() {
//        customTab.bindCustomTabsService(this@AboutActivity)
//      }
//
//      @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
//      fun stop() {
//        customTab.unbindCustomTabsService(this@AboutActivity)
//      }
//
//      @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
//      fun destroy() {
//        customTab.connectionCallback = null
//      }
//    })
    lifecycle()
        .doOnStart(customTab) { bindCustomTabsService(this@AboutActivity) }
        .doOnStop(customTab) { unbindCustomTabsService(this@AboutActivity) }
        .doOnDestroy(customTab) { connectionCallback = null }
        .autoDispose(this)
        .subscribe()

    val viewGroup = viewContainer.forActivity(this)
    layoutInflater.inflate(R.layout.activity_generic_container, viewGroup)

    if (savedInstanceState == null) {
      supportFragmentManager.commitNow {
        add(R.id.fragment_container, AboutFragment())
      }
    }
  }

  @dagger.Module
  abstract class Module : ActivityModule<AboutActivity>
}

class AboutFragment : InjectingBaseFragment() {

  companion object {
    private const val FADE_PERCENT = 0.75F
    private const val TITLE_TRANSLATION_PERCENT = 0.50F
  }

  @Inject
  internal lateinit var linkManager: LinkManager
  @Inject
  internal lateinit var markwon: Markwon
  @Inject
  internal lateinit var versionInfo: VersionInfo

  private val rootLayout by bindView<CoordinatorLayout>(R.id.about_fragment_root)
  private val appBarLayout by bindView<AppBarLayout>(R.id.appbarlayout)
  private val bannerContainer by bindView<View>(R.id.banner_container)
  private val bannerIcon by bindView<ImageView>(R.id.banner_icon)
  private val aboutText by bindView<TextView>(R.id.banner_text)
  private val title by bindView<TextView>(R.id.banner_title)
  private val tabLayout by bindView<TabLayout>(R.id.tab_layout)
  private val toolbar by bindView<Toolbar>(R.id.toolbar)
  private val viewPager by bindView<ViewPager2>(R.id.view_pager) {
    it.offscreenPageLimit = 1
  }

  private lateinit var compositeClickSpan: (String) -> Set<Any>

  override fun inflateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View =
      inflater.inflate(R.layout.fragment_about, container, false)

  override fun onSaveInstanceState(outState: Bundle) {
    (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior?.let { behavior ->
      outState.putParcelable("collapsingToolbarState",
          behavior.onSaveInstanceState(rootLayout, appBarLayout))
    }
    outState.putParcelable("aboutAdapter", (viewPager.adapter as FragmentStateAdapter).saveState())
    super.onSaveInstanceState(outState)
  }

  @SuppressLint("SetTextI18n")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val pagerAdapter = object : FragmentStateAdapter(childFragmentManager, lifecycle) {
      private val screens = SparseArray<Fragment>()

      override fun getItemCount(): Int = 2

      override fun createFragment(position: Int): Fragment {
        return screens.get(position) ?: run {
          when (position) {
            0 -> LicensesFragment()
            1 -> ChangelogFragment()
            else -> TODO("Not implemented")
          }.also {
            screens.put(position, it)
          }
        }
      }

      fun getFragment(position: Int): Fragment? {
        return screens.get(position)
      }
    }

    compositeClickSpan = { url: String ->
      setOf(
          object : TouchableUrlSpan(url, aboutText.linkTextColors, 0) {
            override fun onClick(url: String) {
              viewLifecycleOwner.lifecycleScope.launch {
                linkManager.openUrl(
                    UrlMeta(url, aboutText.highlightColor,
                        activity!!))
              }
            }
          },
          StyleSpan(Typeface.BOLD)
      )
    }

    savedInstanceState?.let { state ->
      state.getParcelable<Parcelable>("collapsingToolbarState")?.let {
        (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior
            ?.onRestoreInstanceState(rootLayout, appBarLayout, it)
      }
      state.getParcelable<Parcelable>("aboutAdapter")?.let {
        pagerAdapter.restoreState(it)
      }
    }

    with(activity as AppCompatActivity) {
      if (!isInNightMode()) {
        toolbar.setLightStatusBar()
      }
      setSupportActionBar(toolbar)
      supportActionBar?.run {
        setDisplayHomeAsUpEnabled(true)
        setDisplayShowTitleEnabled(false)
      }
    }

    bannerIcon.setOnClickListener {
      appBarLayout.setExpanded(false, true)
    }
    bannerIcon.setOnLongClickListener {
      Toast.makeText(activity, R.string.icon_attribution, Toast.LENGTH_SHORT).show()
      viewLifecycleOwner.lifecycleScope.launch {
        linkManager.openUrl(
            UrlMeta("https://cookicons.co", aboutText.highlightColor, activity!!))
      }
      true
    }

    aboutText.movementMethod = LinkTouchMovementMethod.getInstance()
    aboutText.text = buildMarkdown {
      text(aboutText.resources.getString(R.string.about_description))
      newline(3)
      text(aboutText.resources.getString(R.string.about_version, versionInfo.name))
      newline(2)
      text(aboutText.resources.getString(R.string.about_by))
      space()
      link("https://twitter.com/ZacSweers", "Zac Sweers")
      text(" - ")
      link("https://github.com/ZacSweers/CatchUp",
          aboutText.resources.getString(R.string.about_source_code))
    }.parseMarkdownAndPlainLinks(
        on = aboutText,
        with = markwon,
        alternateSpans = compositeClickSpan)

    // Set up pager
    viewPager.adapter = pagerAdapter
    TabLayoutMediator(tabLayout, viewPager) { tab, position ->
      viewPager.setCurrentItem(tab.position, true)
      tab.text = when (position) {
        0 -> resources.getString(R.string.licenses)
        else -> resources.getString(R.string.changelog)
      }
    }.attach()

    tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
      override fun onTabSelected(tab: TabLayout.Tab) {}

      override fun onTabUnselected(tab: TabLayout.Tab) {}

      override fun onTabReselected(tab: TabLayout.Tab) {
        pagerAdapter.getFragment(tab.position).let {
          if (it is Scrollable) {
            it.onRequestScrollToTop()
          }
        }
      }
    })

    // Wait till things are measured
    val callSetUpHeader = { setUpHeader() }
    if (!appBarLayout.isLaidOut) {
      appBarLayout.post { callSetUpHeader() }
    } else {
      callSetUpHeader()
    }
  }

  @SuppressLint("CheckResult")
  private fun setUpHeader() {
    // TODO would be good if we could be smarter about scroll distance and tweak the offset
    // thresholds to dynamically adjust. Case and point - don't want tablayout below to cover the
    // text before it's fully faded out
    val parallaxMultiplier = (bannerContainer.layoutParams as CollapsingToolbarLayout.LayoutParams).parallaxMultiplier

    val interpolator = UiUtil.fastOutSlowInInterpolator

    // X is pretty simple, we just want to end up 72dp to the end of start
    @Px val finalAppBarHeight = toolbar.measuredHeight * 2
    @Px val translatableHeight = appBarLayout.measuredHeight - finalAppBarHeight
    @Px val titleX = title.x
    @Px val titleInset = toolbar.titleMarginStart
    @Px val desiredTitleX = if (Locale.getDefault().layoutDirection == View.LAYOUT_DIRECTION_RTL) {
      // I have to subtract 2x to line this up correctly
      // I have no idea why
      toolbar.measuredWidth - titleInset - titleInset
    } else {
      titleInset
    }
    @Px val xDelta = titleX - desiredTitleX

    // Y values are a bit trickier - these need to figure out where they would be on the larger
    // plane, so we calculate it upfront by predicting where it would land after collapse is done.
    // This requires knowing the parallax multiplier and adjusting for the parent plane rather
    // than the relative plane of the internal LL. Once we know the predicted global Y, easy to
    // calculate desired delta from there.
    @Px val titleY = title.y
    @Px val desiredTitleY = (toolbar.measuredHeight - title.measuredHeight) / 2
    @Px val predictedFinalY = titleY - (translatableHeight * parallaxMultiplier)
    @Px val yDelta = desiredTitleY - predictedFinalY

    viewLifecycleOwner.lifecycleScope.launch {
      /*
     * Here we want to get the appbar offset changes paired with the direction it's moving and
     * using `offsetChanges` API to make an rx Observable of this. The first
     * part buffers two while skipping one at a time and emits "scroll direction" enums. Second
     * part is just a simple map to pair the offset with the resolved scroll direction comparing
     * to the previous offset. This gives us a nice stream of (offset, direction) emissions.
     *
     * Note that the filter() is important if you manipulate child views of the ABL. If any child
     * view requests layout again, it will trigger an emission from the offset listener with the
     * same value as before, potentially causing measure/layout/draw thrashing if your logic
     * reacting to the offset changes *is* manipulating those child views (vicious cycle).
     */
      appBarLayout.offsetChanges()
          .windowed(2, 1) // Buffer in pairs to compare the previous, skip none
          .filter { it[1] != it[0] }
          .map {
            // Map to a direction
            it[1] to ScrollDirection.resolve(it[1], it[0])
          }
          .collect { (offset, _) ->
            // Note: Direction is unused for now but left because this was neat
            val percentage = abs(offset).toFloat() / translatableHeight

            // Force versions outside boundaries to be safe
            if (percentage > FADE_PERCENT) {
              bannerIcon.alpha = 0F
              aboutText.alpha = 0F
            }
            if (percentage < TITLE_TRANSLATION_PERCENT) {
              title.translationX = 0F
              title.translationY = 0F
            }
            if (percentage < FADE_PERCENT) {
              // We want to accelerate fading to be the first [FADE_PERCENT]% of the translation,
              // so adjust accordingly below and use the new calculated percentage for our
              // interpolation
              val adjustedPercentage = 1 - (percentage * (1.0F / FADE_PERCENT))
              val interpolation = interpolator.getInterpolation(adjustedPercentage)
              bannerIcon.alpha = interpolation
              aboutText.alpha = interpolation
            }
            if (percentage > TITLE_TRANSLATION_PERCENT) {
              // Start translating about halfway through (to give a staggered effect next to the alpha
              // so they have time to fade out sufficiently). From here we just set translation offsets
              // to adjust the position naturally to give the appearance of settling in to the right
              // place.
              val adjustedPercentage = (1 - percentage) * (1.0F / TITLE_TRANSLATION_PERCENT)
              val interpolation = interpolator.getInterpolation(adjustedPercentage)
              title.translationX = -(xDelta - (interpolation * xDelta))
              title.translationY = yDelta - (interpolation * yDelta)
            }
          }
    }
  }
}

@Module
abstract class AboutFragmentBindingModule {

  @PerFragment
  @ContributesAndroidInjector
  internal abstract fun aboutFragment(): AboutFragment

  @PerFragment
  @ContributesAndroidInjector
  internal abstract fun licensesFragment(): LicensesFragment

  @PerFragment
  @ContributesAndroidInjector
  internal abstract fun changelogFragment(): ChangelogFragment
}

private enum class ScrollDirection {
  UP, DOWN;

  companion object {
    fun resolve(current: Int, prev: Int): ScrollDirection {
      return if (current > prev) {
        DOWN
      } else {
        UP
      }
    }
  }
}
