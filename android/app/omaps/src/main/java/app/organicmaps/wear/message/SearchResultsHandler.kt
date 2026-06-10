package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.SearchResultItem
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class SearchResultsHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 1) return
        val isSearching = buffer.get().toInt() == 1
        val results = mutableListOf<SearchResultItem>()
        android.util.Log.d("SearchResultsHandler", "Handling results. isSearching=$isSearching Remaining bytes=${buffer.remaining()}")
        while (buffer.remaining() >= 4) {
            val nameLen = buffer.int
            if (buffer.remaining() < nameLen) break
            val name = String(data, buffer.position(), nameLen, StandardCharsets.UTF_8)
            buffer.position(buffer.position() + nameLen)
            
            if (buffer.remaining() < 4) break
            val descLen = buffer.int
            val desc = if (descLen > 0 && buffer.remaining() >= descLen) {
                val s = String(data, buffer.position(), descLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + descLen)
                s
            } else ""
            
            if (buffer.remaining() < 16) break
            val lat = buffer.double
            val lon = buffer.double

            val distLen = if (buffer.remaining() >= 4) buffer.int else 0
            val dist = if (distLen > 0 && buffer.remaining() >= distLen) {
                val s = String(data, buffer.position(), distLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + distLen)
                s
            } else ""

            val featureLen = if (buffer.remaining() >= 4) buffer.int else 0
            val feature = if (featureLen > 0 && buffer.remaining() >= featureLen) {
                val s = String(data, buffer.position(), featureLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + featureLen)
                s
            } else ""

            results.add(SearchResultItem(
                name = name, 
                description = desc, 
                lat = lat, 
                lon = lon, 
                distance = dist, 
                featureType = feature
            ))
        }
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            searchResults = results,
            isSearching = isSearching
        ), force = true)
    }
}
