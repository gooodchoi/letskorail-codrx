package com.example.letskorail

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val py = Python.getInstance()
        val bridge = py.getModule("korail_bridge")

        val idInput = findViewById<EditText>(R.id.editUserId)
        val pwInput = findViewById<EditText>(R.id.editPassword)
        val loginButton = findViewById<Button>(R.id.buttonLogin)
        val reserveButton = findViewById<Button>(R.id.buttonReserve)
        resultText = findViewById(R.id.textResult)

        loginButton.setOnClickListener {
            val id = idInput.text.toString()
            val pw = pwInput.text.toString()

            val result = callPython(bridge, "login", id, pw)
            resultText.text = "[로그인 결과]\n$result"
        }

        reserveButton.setOnClickListener {
            val id = idInput.text.toString()
            val pw = pwInput.text.toString()

            val result = callPython(bridge, "reserve", id, pw)
            resultText.text = "[예매 결과]\n$result"
        }
    }

    private fun callPython(module: PyObject, functionName: String, vararg args: Any): String {
        return try {
            module.callAttr(functionName, *args).toString()
        } catch (e: Exception) {
            "오류: ${e.message}"
        }
    }
}
