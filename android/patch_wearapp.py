import re
import os

filepath = 'android/app/omaps/src/main/java/app/organicmaps/wear/WearApplication.kt'

with open(filepath, 'r') as f:
    content = f.read()

old_block = """        MainScope().launch(Dispatchers.Main) {
            try {
                organicMaps.init { 
                    isFullyInitialized = true 
                }
            } catch (e: Throwable) {
                initError = e.stackTraceToString()
                e.printStackTrace()
            }
        }"""

new_block = """        try {
            organicMaps.init { 
                isFullyInitialized = true 
            }
        } catch (e: Throwable) {
            initError = e.stackTraceToString()
            e.printStackTrace()
        }"""

content = content.replace(old_block, new_block)

with open(filepath, 'w') as f:
    f.write(content)
