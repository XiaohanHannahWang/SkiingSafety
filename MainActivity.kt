package com.example.hellowandriod


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import com.example.hellowandriod.ui.theme.HellowAndriodTheme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument


val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HellowAndriodTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation()
                }
            }
        }
    }
}
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(navController)
        }
        composable("second") {
            SecondScreen(
                navController)
        }
        composable("third") {
            ThirdScreen(navController)
        }

        composable(
            route = "fourth/{sliderPosition1}/{sliderPosition2}",
            arguments = listOf(
                navArgument("sliderPosition1") {
                    defaultValue = "0.00"
                },
                navArgument("sliderPosition2") {
                    defaultValue = "0.00"
                },

            )
        ) { backStackEntry ->
            val sliderPosition1 =
                backStackEntry.arguments?.getString("sliderPosition1")?.toDoubleOrNull() ?: 0.00
            val sliderPosition2 =
                backStackEntry.arguments?.getString("sliderPosition2")?.toDoubleOrNull() ?: 0.00
            FourthScreen(navController, sliderPosition1, sliderPosition2)
        }
    }
}


@Composable
fun MainScreen(navController: NavController) {

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Hi,",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "it's torpedo detector!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { navController.navigate("second") },

        ) {
            Text(
                text = "Add a webcam",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {navController.navigate("third")},

        ) {
            Text(
                text = "Use phone camera",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SecondScreen(navController: NavController) {
    var sliderPosition1 by remember{mutableStateOf(0f)}
    var sliderPosition2 by remember{mutableStateOf(0f)}
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text="sensitivity to velocity  %.2f".format(sliderPosition1))
        Slider(
            value=sliderPosition1,
            valueRange=0f..100f,
            onValueChange={sliderPosition1=it})
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = "sensitivity to distance  %.2f".format(sliderPosition2))
        Slider(
            value = sliderPosition2,
            valueRange = 0f..100f,
            onValueChange = { sliderPosition2 = it })
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
            val sliderPosition1 = sliderPosition1
            val sliderPosition2 = sliderPosition2
            navController.navigate("fourth/$sliderPosition1/$sliderPosition2")}) {
            Text("save and run")
        }
    }
}

@Composable
fun ThirdScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Welcome!", fontSize = 20.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { navController.navigate("fourth")}) {
            Text("save and run")
        }
    }
}

@Composable
fun FourthScreen(    navController: NavController,
                     sliderPosition1: Double,
                     sliderPosition2: Double) {
//    var sliderPosition1 by remember{mutableStateOf(0f)}
//    var sliderPosition2 by remember{mutableStateOf(0f)}
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Torpedo detector",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
                text = "Running",
        fontSize = 44.sp,
        fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "sensitivity to velocity ${sliderPosition1}",
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "sensitivity to velocity ${sliderPosition2}",
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { navController.popBackStack() }) {
            Text("Change settings")
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { navController.navigate("main")}) {
            Text("End and go back")
        }
    }
}

//@Composable
//fun Slider1(){
//    var sliderPosition1 by remember{mutableStateOf(0f)}
//    Text(text="sensitivity to velocity  %.2f".format(sliderPosition1))
//    Slider(
//        value=sliderPosition1,
//        valueRange=0f..100f,
//        onValueChange={sliderPosition1=it})
//    Spacer(modifier = Modifier.height(32.dp))
//}
//
//@Composable
//fun Slider2() {
//    var sliderPosition2 by remember { mutableStateOf(0f) }
//    Text(text = "sensitivity to distance  %.2f".format(sliderPosition2))
//    Slider(
//        value = sliderPosition2,
//        valueRange = 0f..100f,
//        onValueChange = { sliderPosition2 = it })
//    Spacer(modifier = Modifier.height(32.dp))
//}
fun align(center: Alignment) {}



