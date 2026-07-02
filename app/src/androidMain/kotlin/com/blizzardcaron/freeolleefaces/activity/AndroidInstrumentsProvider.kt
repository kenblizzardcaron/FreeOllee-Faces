package com.blizzardcaron.freeolleefaces.activity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-sensor instruments: rotation vector -> compass azimuth, barometer -> hPa. A missing
 * sensor simply leaves its field null (the idle row hides that readout). [stop] clears the
 * readings so a stale heading never flashes on the next start.
 */
class AndroidInstrumentsProvider(context: Context) : InstrumentsProvider, SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val pressure: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _instruments = MutableStateFlow(IdleInstruments())
    override val instruments: StateFlow<IdleInstruments> = _instruments.asStateFlow()

    override fun start() {
        rotation?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressure?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        _instruments.value = IdleInstruments()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(ORIENTATION_SIZE)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuth =
                    (Math.toDegrees(orientation[0].toDouble()).toFloat() + DEGREES_FULL) % DEGREES_FULL
                _instruments.value = _instruments.value.copy(headingDeg = azimuth)
            }
            Sensor.TYPE_PRESSURE ->
                _instruments.value = _instruments.value.copy(pressureHpa = event.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ROTATION_MATRIX_SIZE = 9
        const val ORIENTATION_SIZE = 3
        const val DEGREES_FULL = 360f
    }
}
