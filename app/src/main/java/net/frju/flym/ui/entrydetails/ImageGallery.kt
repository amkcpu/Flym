package net.frju.flym.ui.entrydetails

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.ImageView
import net.fred.feedex.R

class ImageGallery : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_gallery)

        val uri = intent.getData()

        val imageView = findViewById<ImageView>(R.id.imageView).setImageURI(uri)
    }
}
