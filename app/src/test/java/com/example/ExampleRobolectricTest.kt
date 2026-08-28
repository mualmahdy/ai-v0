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

    org.junit.Assert.assertNotNull(viewModel.uiState.value.activeAgent)
    org.junit.Assert.assertTrue(viewModel.uiState.value.availableAgents.isNotEmpty())
    org.junit.Assert.assertEquals(com.example.presentation.state.ActiveNavigationTab.STUDIO, viewModel.uiState.value.activeTab)
  }
}
