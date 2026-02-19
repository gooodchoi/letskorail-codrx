package com.example.letskorail

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private lateinit var loginErrorText: TextView
    private lateinit var loginContainer: LinearLayout
    private lateinit var reserveContainer: LinearLayout

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
        loginErrorText = findViewById(R.id.textLoginError)
        loginContainer = findViewById(R.id.loginContainer)
        reserveContainer = findViewById(R.id.reserveContainer)

        pwInput.addTextChangedListener(SimpleTextWatcher {
            loginErrorText.visibility = View.GONE
        })

        loginButton.setOnClickListener {
            val id = idInput.text.toString()
            val pw = pwInput.text.toString()

            val result = callPython(bridge, "login", id, pw)
            if (result.startsWith("로그인 성공")) {
                Toast.makeText(this, "로그인 성공", Toast.LENGTH_SHORT).show()
                loginErrorText.visibility = View.GONE
                showReservePage()
            } else {
                loginErrorText.visibility = View.VISIBLE
            }
        }

        reserveButton.setOnClickListener {
            val id = idInput.text.toString()
            val pw = pwInput.text.toString()

            val result = callPython(bridge, "reserve", id, pw)
            resultText.text = "[예매 결과]\n$result"
        }
    }

    private fun showReservePage() {
        loginContainer.visibility = View.GONE
        reserveContainer.visibility = View.VISIBLE
    }

    private fun callPython(module: PyObject, functionName: String, vararg args: Any): String {
        return try {
            module.callAttr(functionName, *args).toString()
        } catch (e: Exception) {
            "오류: ${e.message}"
        }
    }
}
