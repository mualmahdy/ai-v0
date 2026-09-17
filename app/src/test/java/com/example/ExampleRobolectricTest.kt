package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AI-V0 Ultimate", appName)
  }

  @Test
  fun `test AppContainer and MainViewModel initialization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val container = com.example.presentation.di.AppContainer(context)
    val factory = com.example.presentation.di.MainViewModelFactory(container)
    val viewModel = factory.create(com.example.presentation.viewmodel.MainViewModel::class.java)

    // (ADR-6 slice 7) MainViewModel is the honest APP SHELL now: the agent
    // catalog lives in AgentsViewModel (its owner). The shell's bootstrap
    // gate state is what this construction honestly carries.
    org.junit.Assert.assertNotNull(viewModel.uiState.value.bootstrapPhase)
    org.junit.Assert.assertNull(viewModel.uiState.value.bootstrapFailureMessage)
    // The feature factories construct over the same real container.
    val agentsFactory = com.example.presentation.di.AgentsViewModelFactory(container)
    val agentsViewModel = agentsFactory.create(com.example.presentation.viewmodel.AgentsViewModel::class.java)
    org.junit.Assert.assertNotNull(agentsViewModel.state.value.activeAgent)
    org.junit.Assert.assertTrue(agentsViewModel.state.value.availableAgents.isNotEmpty())
  }
}
