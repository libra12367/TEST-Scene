package com.system.tools.fragments

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.system.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.ProgressBarDialog
import com.system.data.GlobalStatus
import com.system.library.shell.*
import com.system.model.CpuCoreInfo
import com.system.scene_mode.CpuConfigInstaller
import com.system.scene_mode.ModeSwitcher
import com.system.store.SpfConfig
import com.system.ui.AdapterCpuCores
import com.system.utils.AccessibleServiceHelper
import com.system.tools.R
import com.system.tools.dialogs.DialogElectricityUnit
import kotlinx.android.synthetic.main.fragment_home.*
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.collections.HashMap

class FragmentHome : androidx.fragment.app.Fragment() {
    private val modeSwitcher = ModeSwitcher()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    private var CpuFrequencyUtil = CpuFrequencyUtils()
    private lateinit var globalSPF: SharedPreferences
    private var timer: Timer? = null

    private lateinit var spf: SharedPreferences
    private var myHandler = Handler(Looper.getMainLooper())
    private var cpuLoadUtils = CpuLoadUtils()
    private val memoryUtils = MemoryUtils()

    private suspend fun forceKSWAPD(mode: Int): String {
        return withContext(Dispatchers.Default) {
            SwapUtils(context!!).forceKswapd(mode)
        }
    }

    private suspend fun dropCaches() {
        return withContext(Dispatchers.Default) {
            KeepShellPublic.doCmdSync(
                    "sync\n" +
                            "echo 3 > /proc/sys/vm/drop_caches\n" +
                            "echo 1 > /proc/sys/vm/compact_memory")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activityManager = context!!.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        batteryManager = context!!.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        globalSPF = context!!.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        btn_powersave.setOnClickListener {
            installConfig(ModeSwitcher.POWERSAVE)
        }
        btn_defaultmode.setOnClickListener {
            installConfig(ModeSwitcher.BALANCE)
        }
        btn_gamemode.setOnClickListener {
            installConfig(ModeSwitcher.PERFORMANCE)
        }
        btn_fastmode.setOnClickListener {
            installConfig(ModeSwitcher.FAST)
        }

        if (!GlobalStatus.homeMessage.isNullOrEmpty()) {
            home_message.visibility = View.VISIBLE
            home_message.text = GlobalStatus.homeMessage
        }

        spf = context!!.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        home_clear_ram.setOnClickListener {
            home_raminfo_text.text = getString(R.string.please_wait)
            GlobalScope.launch(Dispatchers.Main) {
                dropCaches()
                Scene.toast("缓存已清理...", Toast.LENGTH_SHORT)
            }
        }

        home_clear_swap.setOnClickListener {
            home_zramsize_text.text = getText(R.string.please_wait)
            GlobalScope.launch(Dispatchers.Main) {
                Scene.toast("开始回收少量内存(长按回收更多~)", Toast.LENGTH_SHORT)
                val result = forceKSWAPD(1)
                Scene.toast(result, Toast.LENGTH_SHORT)
            }
        }

        home_clear_swap.setOnLongClickListener {
            home_zramsize_text.text = getText(R.string.please_wait)
            GlobalScope.launch(Dispatchers.Main) {
                val result = forceKSWAPD(2)
                Scene.toast(result, Toast.LENGTH_SHORT)
            }
            true
        }

        home_help.setOnClickListener {
            try {
                val uri = Uri.parse("http://vtools.omarea.com/")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(context!!, "启动在线页面失败！", Toast.LENGTH_SHORT).show()
            }
        }

        home_battery_edit.setOnClickListener {
            DialogElectricityUnit().showDialog(context!!)
        }

        home_device_name.text = when (Build.VERSION.SDK_INT) {
            31 -> "Android 12"
            30 -> "Android 11"
            29 -> "Android 10"
            28 -> "Android 9"
            27 -> "Android 8.1"
            26 -> "Android 8.0"
            25 -> "Android 7.0"
            24 -> "Android 7.0"
            23 -> "Android 6.0"
            22 -> "Android 5.1"
            21 -> "Android 5.0"
            else -> "SDK(" + Build.VERSION.SDK_INT + ")"
        } // (Build.MANUFACTURER + " " + Build.MODEL + " (SDK" + Build.VERSION.SDK_INT + ")").trim()
    }

    @SuppressLint("SetTextI18n")
    override fun onResume() {
        super.onResume()
        if (isDetached) {
            return
        }
        maxFreqs.clear()
        minFreqs.clear()
        stopTimer()
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                updateInfo()
            }
        }, 0, 1000)
        updateRamInfo()
    }

    private var coreCount = -1
    private var activityManager: ActivityManager? = null

    private var minFreqs = HashMap<Int, String>()
    private var maxFreqs = HashMap<Int, String>()
    fun format1(value: Double): String {

        var bd = BigDecimal(value)
        bd = bd.setScale(1, RoundingMode.HALF_UP)
        return bd.toString()
    }

    @SuppressLint("SetTextI18n")
    private fun updateRamInfo() {
        try {
            val info = ActivityManager.MemoryInfo()
            if (activityManager == null) {
                activityManager = context!!.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            }
            activityManager!!.getMemoryInfo(info)
            val totalMem = (info.totalMem / 1024 / 1024f).toInt()
            val availMem = (info.availMem / 1024 / 1024f).toInt()
            home_raminfo_text.text = "${((totalMem - availMem) * 100 / totalMem)}% (${totalMem / 1024 + 1}GB)"
            home_raminfo.setData(totalMem.toFloat(), availMem.toFloat())
            val swapInfo = KeepShellPublic.doCmdSync("free -m | grep Swap")
            if (swapInfo.contains("Swap")) {
                try {
                    val swapInfos = swapInfo.substring(swapInfo.indexOf(" "), swapInfo.lastIndexOf(" ")).trim()
                    if (Regex("[\\d]{1,}[\\s]{1,}[\\d]{1,}").matches(swapInfos)) {
                        val total = swapInfos.substring(0, swapInfos.indexOf(" ")).trim().toInt()
                        val use = swapInfos.substring(swapInfos.indexOf(" ")).trim().toInt()
                        val free = total - use
                        home_swapstate_chat.setData(total.toFloat(), free.toFloat())
                        if (total > 99) {
                            home_zramsize_text.text = "${(use * 100.0 / total).toInt()}% (${format1(total / 1024.0)}GB)"
                        } else {
                            home_zramsize_text.text = "${(use * 100.0 / total).toInt()}% (${total}MB)"
                        }
                    }
                } catch (ex: java.lang.Exception) {
                }
                // home_swapstate.text = swapInfo.substring(swapInfo.indexOf(" "), swapInfo.lastIndexOf(" ")).trim()
            } else {
            }
        } catch (ex: Exception) {
        }
    }

    private var updateTick = 0

    @SuppressLint("SetTextI18n")
    private fun updateInfo() {
        if (coreCount < 1) {
            coreCount = CpuFrequencyUtils.getCoreCount()
        }
        val cores = ArrayList<CpuCoreInfo>()
        val loads = CpuFrequencyUtils.getCpuLoad()
        for (coreIndex in 0 until coreCount) {
            val core = CpuCoreInfo()

            core.currentFreq = CpuFrequencyUtils.getCurrentFrequency("cpu$coreIndex")
            if (!maxFreqs.containsKey(coreIndex) || (core.currentFreq != "" && maxFreqs.get(coreIndex).isNullOrEmpty())) {
                maxFreqs.put(coreIndex, CpuFrequencyUtils.getCurrentMaxFrequency("cpu" + coreIndex))
            }
            core.maxFreq = maxFreqs.get(coreIndex)

            if (!minFreqs.containsKey(coreIndex) || (core.currentFreq != "" && minFreqs.get(coreIndex).isNullOrEmpty())) {
                minFreqs.put(coreIndex, CpuFrequencyUtils.getCurrentMinFrequency("cpu" + coreIndex))
            }
            core.minFreq = minFreqs.get(coreIndex)

            if (loads.containsKey(coreIndex)) {
                core.loadRatio = loads.get(coreIndex)!!
            }
            cores.add(core)
        }
        val gpuFreq = GpuUtils.getGpuFreq() + "%"
        val gpuLoad = GpuUtils.getGpuLoad()
        myHandler.post {
            try {
                cpu_core_count.text = String.format(getString(R.string.monitor_core_count), coreCount)

                home_gpu_freq.text = gpuFreq
                home_gpu_load.text = String.format(getString(R.string.monitor_laod1), gpuLoad)
                if (gpuLoad > -1) {
                    home_gpu_chat.setData(100.toFloat(), (100 - gpuLoad).toFloat())
                }
                if (loads.containsKey(-1)) {
                    cpu_core_total_load.text = String.format(getString(R.string.monitor_laod), loads.get(-1)!!.toInt())
                    home_cpu_chat.setData(100.toFloat(), (100 - loads.get(-1)!!.toInt()).toFloat())
                }
                if (cpu_core_list.adapter == null) {
                    if (cores.size < 6) {
                        cpu_core_list.numColumns = 2
                    }
                    cpu_core_list.adapter = AdapterCpuCores(context!!, cores)
                } else {
                    (cpu_core_list.adapter as AdapterCpuCores).setData(cores)
                }
            } catch (ex: Exception) {
                Log.e("Exception", ex.message)
            }
        }
        updateTick++
        if (updateTick > 5) {
            updateTick = 0
            minFreqs.clear()
            maxFreqs.clear()
        }
    }

    private fun stopTimer() {
        if (this.timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    override fun onPause() {
        stopTimer()
        super.onPause()
    }
}
