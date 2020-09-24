package dev.android.likespace.todaycalendar

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.collection.LruCache
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import dev.android.likespace.R
import dev.android.likespace.todaycalendar.NasaDataConverter.jsonToRemote
import dev.android.likespace.todaycalendar.NasaDataConverter.remoteToScreen
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.fragment_today_calendar.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resumeWithException

private const val TAG = "TodayCalendar"

private const val SAVED_STATE_SCREEN_DATA = "SAVED_STATE_SCREEN_DATA"

@Parcelize
data class UiModel<T : Parcelable>(
    val data: T? = null,
    val error: Throwable? = null,
    val progress: Boolean = false
) : Parcelable

@Parcelize
data class ScreenData(
    val date: Date? = null,
    val title: String? = null,
    val description: String? = null
) : Parcelable

class TodayCalendarFragment : Fragment(R.layout.fragment_today_calendar) {

    private var screenDate: Date = Calendar.getInstance().time
    private val screenData = MutableLiveData<UiModel<ScreenData>?>()
    private val screenCache = LruCache<Date, ScreenData>(100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_today_calendar, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_choose_date -> {
                doChoseDate(screenDate); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(SAVED_STATE_SCREEN_DATA, screenData.value)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        savedInstanceState?.getParcelable<UiModel<ScreenData>>(SAVED_STATE_SCREEN_DATA)
            ?.also { instance ->
                screenData.value = instance
            }

        screenData.observe(viewLifecycleOwner) { model ->
            model?.progress?.also {
                showScreenData(ScreenData(title = "loading"))
            }
            model?.error?.also { error ->
                showScreenData(ScreenData(title = "error", description = error.message))
            }
            model?.data?.also { data ->
                showScreenData(data)
            }
        }
        refreshScreenData(cache = screenData.value?.data)
    }

    private fun showScreenData(data: ScreenData) = view?.apply {
        text_date.text = data.date?.let { NasaDataConverter.dateFormat(it) }
        text_title.text = data.title
        text_description.text = data.description
    }

    private fun refreshScreenData(date: Date? = null, cache: ScreenData? = null) {
        val loadDate = date ?: cache?.date ?: screenDate
        val loadCache = cache ?: screenCache.get(loadDate) ?: ScreenData(date, "loading..")
        lifecycleScope.launch {
            screenData.value = UiModel(progress = true, data = loadCache)
            screenData.value = try {
                val data = fetchScreenData(loadDate)
                screenCache.put(loadDate, data)
                UiModel(data = data)
            } catch (error: Throwable) {
                Log.w(TAG, error); UiModel(error = error)
            }
        }
    }

    private suspend fun fetchScreenData(date: Date) =
        fetchApodRemote(date)
        .remoteToScreen()


    private fun doChoseDate(current: Date? = null) = lifecycleScope.launchWhenResumed {
        chooseDate(requireActivity(), current)?.let { date ->
            if (date.after(Calendar.getInstance().time)) {
                Toast.makeText(
                    requireContext(),
                    "Please select current date or before",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                screenDate = date
                refreshScreenData(date)
            }
        }
    }

}

data class NasaApodResp(
    val date: String?,
    val title: String?,
    val explanation: String?
)

private suspend fun fetchApodRemote(date: Date) =
    fetchDataRemote(
        "https://api.nasa.gov/planetary/apod" +
                "?api_key=DEMO_KEY" +
                "&date=${NasaDataConverter.dateFormat(date)}"
    ).jsonToRemote()

object NasaDataConverter {

    const val NASA_DATE_FORMAT = "yyyy-MM-dd"

    private val dateFormatter: SimpleDateFormat
        get() = SimpleDateFormat(NASA_DATE_FORMAT, Locale.US)

    fun dateParse(date: String) = dateFormatter.parse(date)
    fun dateFormat(date: Date) = dateFormatter.format(date)

    fun NasaApodResp.remoteToScreen(): ScreenData {
        return ScreenData(
            date?.let { dateParse(it) },
            title,
            explanation
        )
    }

    fun String.jsonToRemote(): NasaApodResp =
        with(JSONObject(this)) {
            NasaApodResp(
                date = optString("date"),
                title = optString("title"),
                explanation = optString("explanation")
            )
        }

}


private suspend fun fetchDataRemote(url: String): String = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        try {
            val response = URL(url)
                .readText()
            continuation
                .takeIf { it.isActive }
                ?.resumeWith(Result.success(response))
        } catch (error: Throwable) {
            continuation.resumeWithException(
                RuntimeException("Load data Failed!", error)
            )
        } finally {
            continuation.cancel()
        }
    }
}

private suspend fun chooseDate(activity: Activity, current: Date?): Date? =
    suspendCancellableCoroutine { continuation ->
        val calendar = Calendar.getInstance()
        calendar.time = current ?: calendar.time
        val dialog = DatePickerDialog(
            activity,
            { _, y, m, d ->
                calendar.set(y, m, d)
                continuation
                    .takeIf { it.isActive }
                    ?.resumeWith(Result.success(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.setOnCancelListener { continuation.cancel() }
        continuation.invokeOnCancellation {
            dialog.dismiss()
        }
        dialog.show()
    }