package com.example

import com.example.model.SimulatedAdType
import com.example.viewmodel.GuardViewModel
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testCountdownFinishedResumesDrama() {
    val viewModel = GuardViewModel()
    val initialEp = viewModel.uiState.value.currentEpisode
    viewModel.toggleFastSkipBlackScreen(false)
    viewModel.toggleAutoSkipEngine(false)

    // Trigger ad without fast skip
    viewModel.triggerSimulatedAd(SimulatedAdType.EPISODE_INTERSTITIAL)
    assertEquals(SimulatedAdType.EPISODE_INTERSTITIAL, viewModel.uiState.value.activeSimulatedAd)

    // When countdown completes, it must automatically restore drama playback
    viewModel.onCountdownFinished(SimulatedAdType.EPISODE_INTERSTITIAL)
    assertNull(viewModel.uiState.value.activeSimulatedAd)
    assertTrue(viewModel.uiState.value.isEpisodeUnlocked)
    assertEquals(initialEp + 1, viewModel.uiState.value.currentEpisode)
  }

  @Test
  fun testFastSkipBlackScreenAd() {
    val viewModel = GuardViewModel()
    val initialEp = viewModel.uiState.value.currentEpisode
    viewModel.toggleFastSkipBlackScreen(true)

    // ad.zijieapi.com is blocked by default DNS rules
    viewModel.triggerSimulatedAd(SimulatedAdType.SPLASH_COUNTDOWN)

    // Fast skip should immediately dismiss black screen and proceed
    assertNull(viewModel.uiState.value.activeSimulatedAd)
    assertTrue(viewModel.uiState.value.isEpisodeUnlocked)
    assertTrue(viewModel.uiState.value.lastSandboxStatusMessage.contains("已触发黑屏快跳保护"))
  }
}
