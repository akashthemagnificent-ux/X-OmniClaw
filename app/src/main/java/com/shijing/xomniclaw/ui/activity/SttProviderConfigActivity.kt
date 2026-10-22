package com.shijing.xomniclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.ModelDefinition
import com.shijing.xomniclaw.config.ModelsConfig
import com.shijing.xomniclaw.config.ProviderConfig
import com.shijing.xomniclaw.databinding.ActivitySttProviderConfigBinding

/**
 * STT Provider config page.
 *
 * Goals:
 * - Consistent config UX with OpenRouter etc. (dedicated page + tutorial + save)
 * - Maintains models.providers.stt separately
 */
class SttProviderConfigActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SttProviderConfigActivity"
        private const val TUTORIAL_URL = "https://cloud.siliconflow.cn/"
        private const val STT_PROVIDER_ID = "stt"
        private const val STT_DEFAULT_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
        private const val STT_DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    }

    private lateinit var binding: ActivitySttProviderConfigBinding
    private val configLoader by lazy { ConfigLoader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySttProviderConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadCurrentConfig()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    /**
     * Load current STT Provider config on open so user can verify.
     */
    private fun loadCurrentConfig() {
        try {
            val sttProvider = configLoader
                .loadOmniClawConfig()
                .resolveProviders()[STT_PROVIDER_ID]
            binding.etSttApiKey.setText(sttProvider?.apiKey.orEmpty())
            binding.etSttUrl.setText(sttProvider?.baseUrl?.ifBlank { STT_DEFAULT_URL } ?: STT_DEFAULT_URL)
            val modelId = sttProvider?.models?.firstOrNull()?.id?.ifBlank { STT_DEFAULT_MODEL } ?: STT_DEFAULT_MODEL
            binding.etSttModel.setText(modelId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load STT Provider config", e)
            binding.etSttApiKey.setText("")
            binding.etSttUrl.setText(STT_DEFAULT_URL)
            binding.etSttModel.setText(STT_DEFAULT_MODEL)
        }
    }

    private fun setupListeners() {
        binding.btnOpenTutorial.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSave.setOnClickListener {
            saveSttKey()
        }
    }

    /**
     * Save STT config to models.providers.stt.
     */
    private fun saveSttKey() {
        val inputKey = binding.etSttApiKey.text?.toString()?.trim().orEmpty()
        val inputUrl = binding.etSttUrl.text?.toString()?.trim().orEmpty()
        val inputModel = binding.etSttModel.text?.toString()?.trim().orEmpty()
        try {
            val config = configLoader.loadOmniClawConfig()
            val providers = config.resolveProviders().toMutableMap()
            val existing = providers[STT_PROVIDER_ID]
            // URL/模型支持手动配置；为空时回退历史值与默认值，避免清空已有配置。
            val effectiveUrl = when {
                inputUrl.isNotBlank() -> inputUrl
                !existing?.baseUrl.isNullOrBlank() -> existing?.baseUrl.orEmpty()
                else -> STT_DEFAULT_URL
            }
            val effectiveModel = when {
                inputModel.isNotBlank() -> inputModel
                !existing?.models?.firstOrNull()?.id.isNullOrBlank() -> existing?.models?.firstOrNull()?.id.orEmpty()
                else -> STT_DEFAULT_MODEL
            }
            val updatedProvider = ProviderConfig(
                baseUrl = effectiveUrl,
                apiKey = inputKey,
                api = existing?.api ?: "openai-completions",
                auth = existing?.auth,
                authHeader = existing?.authHeader ?: true,
                headers = existing?.headers,
                injectNumCtxForOpenAICompat = existing?.injectNumCtxForOpenAICompat,
                models = listOf(
                    ModelDefinition(
                        id = effectiveModel,
                        name = effectiveModel,
                        contextWindow = 1,
                        maxTokens = 1
                    )
                )
            )
            providers[STT_PROVIDER_ID] = updatedProvider
            val updatedConfig = config.copy(
                models = (config.models ?: ModelsConfig()).copy(
                    providers = providers
                )
            )
            configLoader.saveOmniClawConfig(updatedConfig)
            Toast.makeText(this, "STT Provider saved", Toast.LENGTH_SHORT).show()
            // 显式回传成功结果，便于调用页按“已保存”语义刷新状态。
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save STT Key", e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
