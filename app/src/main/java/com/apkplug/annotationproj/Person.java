package com.apkplug.annotationproj;

import android.util.Log;

import com.apkplug.Export;
import com.apkplug.Service;

/**
 * Created by qinfeng on 16/6/26.
 */
@Service(name = "Call")
public class Person {
    @Export public void call(){
        Log.d("Person","Call mom!");
    }
    @Export public int add(int a, int b){
        return a + b;
    }
}
