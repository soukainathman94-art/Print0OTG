package com.printotg.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** 8x8 Bayer matrix (values 0..63) used for ordered dithering to 1-bit. */
private val BAYER = intArrayOf(
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21
)

/** Print options captured once on the UI thread so the worker thread sees stable values. */
private class Opts(val dpi: Int, val copies: Int, val scalePct: Int, val alignTop: Boolean)

/** An opened, claimed printer interface plus its bulk-OUT endpoint. */
private class Link(val conn: UsbDeviceConnection, val intf: UsbInterface, val ep: UsbEndpoint) {
    fun write(bytes: ByteArray, len: Int = bytes.size) {
        var off = 0
        while (off < len) {
            // Chunks of 16 KB are accepted by every Android version.
            val n = conn.bulkTransfer(ep, bytes, off, minOf(16384, len - off), 30000)
            if (n <= 0) throw IOException("انقطع اتصال USB أثناء الإرسال")
            off += n
        }
    }

    fun cmd(s: String) = write(s.toByteArray(Charsets.US_ASCII))
}

class MainActivity : Activity() {
    private val usbPermission = "com.printotg.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var selectedUri: Uri? = null
    private var selectedName = "لم يتم اختيار ملف"
    private var copies = 1
    private var dpi = 300
    private var scalePct = 100          // 100 = fit page; <100 shrinks, >100 enlarges (crops overflow)
    private var alignTop = false
    private val prefs by lazy { getSharedPreferences("printotg", MODE_PRIVATE) }
    private var status: TextView? = null
    private var fileLabel: TextView? = null
    private var printButton: Button? = null
    private var device: UsbDevice? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == usbPermission) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    status?.text = "تم السماح بالوصول إلى الطابعة. اختر ملفاً للطباعة."
                } else {
                    status?.text = "لم يتم منح إذن USB."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        dpi = if (prefs.getInt("dpi", 300) == 600) 600 else 300
        scalePct = prefs.getInt("scale", 100).coerceIn(25, 200)
        alignTop = prefs.getBoolean("top", false)
        val filter = IntentFilter(usbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        buildUi()
        detectUsb()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        fun heading(t: String, size: Float) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.rgb(25, 45, 70))
            gravity = Gravity.CENTER
        }
        root.addView(heading("Print OTG", 28f))
        root.addView(heading("الطباعة عبر كابل OTG", 16f))
        val printer = heading("الطابعة المستهدفة: HP LaserJet P2055d", 15f)
        printer.setPadding(0, 22, 0, 12); root.addView(printer)

        status = TextView(this).apply {
            text = "جارٍ البحث عن طابعة USB…"; textSize = 14f
            setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "فحص اتصال USB"
            setOnClickListener { detectUsb() }
        })

        root.addView(Button(this).apply {
            text = "اختيار ملف PDF أو صورة"
            setOnClickListener { chooseFile() }
        })

        fileLabel = heading(selectedName, 14f).also { it.setPadding(0, 12, 0, 12) }
        root.addView(fileLabel)

        root.addView(heading("عدد النسخ", 15f))
        val copyBox = EditText(this).apply {
            setText("1"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER; textSize = 18f
        }
        root.addView(copyBox)

        root.addView(heading("جودة الطباعة", 15f))
        val quality = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("300 نقطة/بوصة (أسرع)", "600 نقطة/بوصة (أعلى دقة)")
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    dpi = if (position == 0) 300 else 600
                    prefs.edit().putInt("dpi", dpi).apply()
                }
            }
            setSelection(if (dpi == 600) 1 else 0)
        }
        root.addView(quality)

        // ---- Scale (shrink / enlarge) ----
        root.addView(heading("حجم المحتوى (تصغير / تكبير)", 15f).apply { setPadding(0, 16, 0, 0) })
        val scaleLabel = heading("", 20f)
        root.addView(scaleLabel)
        val seek = SeekBar(this).apply { max = 175 }   // 25%..200%
        fun setScale(v: Int, fromSeek: Boolean = false) {
            scalePct = v.coerceIn(25, 200)
            scaleLabel.text = if (scalePct == 100) "100% (ملاءمة الصفحة)" else "$scalePct%"
            if (!fromSeek) seek.progress = scalePct - 25
            prefs.edit().putInt("scale", scalePct).apply()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setScale(25 + (p / 5) * 5, true)   // 5% steps
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { setScale(scalePct) }
        })
        root.addView(seek)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun btn(t: String, onClick: () -> Unit) = Button(this@MainActivity).apply {
                text = t; setOnClickListener { onClick() }
            }.also { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            btn("− 5%") { setScale(scalePct - 5) }
            btn("100%") { setScale(100) }
            btn("+ 5%") { setScale(scalePct + 5) }
        })
        setScale(scalePct)
        root.addView(CheckBox(this).apply {
            text = "محاذاة للأعلى (بدل التوسيط عند التصغير)"
            isChecked = alignTop
            setOnCheckedChangeListener { _, c ->
                alignTop = c; prefs.edit().putBoolean("top", c).apply()
            }
        })
        root.addView(heading("أقل من 100% يصغّر المحتوى، وأكثر من 100% يكبّره (ما يخرج عن حدود الصفحة يُقصّ).", 12f))

        printButton = Button(this).apply {
            text = "طباعة الآن"
            setOnClickListener {
                copies = copyBox.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1
                startPrint(Opts(dpi, copies, scalePct, alignTop))
            }
        }
        root.addView(printButton)
        root.addView(
            heading("PDF والصور مدعومان مباشرة. لتحويل Word إلى PDF، افتح الملف في Word أو WPS ثم اختر حفظ/تصدير PDF.", 12f)
                .apply { setPadding(4, 20, 4, 0) }
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------------------------------------------------------- USB

    /** Finds the first USB device exposing a printer-class interface with a bulk-OUT endpoint. */
    private fun findPrinter(): Triple<UsbDevice, UsbInterface, UsbEndpoint>? {
        for (d in usbManager.deviceList.values) {
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) return Triple(d, intf, ep)
                }
            }
        }
        return null
    }

    private fun detectUsb() {
        val found = findPrinter()
        if (found == null) {
            device = null
            status?.text = if (usbManager.deviceList.isEmpty())
                "لم يتم العثور على جهاز USB. تحقق من OTG وكابل الطابعة وأن الطابعة مشغّلة."
            else
                "تم العثور على جهاز USB لكنه ليس طابعة. تحقق من الكابل المتصل."
            return
        }
        val d = found.first
        device = d
        if (usbManager.hasPermission(d)) {
            status?.text = "الطابعة جاهزة: ${d.productName ?: d.deviceName}. اختر ملفاً للطباعة."
        } else {
            status?.text = "تم العثور على الطابعة. نطلب إذن الاتصال…"
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(usbPermission).setPackage(packageName), flags
            )
            usbManager.requestPermission(d, pi)
        }
    }

    // --------------------------------------------------------------- files

    private fun chooseFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 100)
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    @Deprecated("Legacy result callback for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            selectedUri = uri
            // Persisting is optional (we only read the file now), so never let it crash the app.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            selectedName = displayName(uri) ?: uri.lastPathSegment ?: "ملف مختار"
            fileLabel?.text = selectedName
        }
    }

    // ----------------------------------------------------------- printing

    private fun startPrint(o: Opts) {
        val uri = selectedUri
        if (uri == null) { status?.text = "يرجى اختيار ملف أولاً."; return }
        val d = device
        if (d == null) { detectUsb(); return }
        if (!usbManager.hasPermission(d)) { detectUsb(); return }

        val name = selectedName.lowercase()
        status?.text = "جارٍ تجهيز الملف…"
        printButton?.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // don't sleep mid-job
        thread {
            var link: Link? = null
            try {
                val found = findPrinter() ?: throw IOException("لم يتم العثور على الطابعة")
                val (dev, intf, ep) = found
                val conn = usbManager.openDevice(dev) ?: throw IOException("تعذر فتح جهاز USB")
                if (!conn.claimInterface(intf, true)) {
                    conn.close()
                    throw IOException("تعذر حجز واجهة الطابعة (قد يكون تطبيق آخر يستخدمها)")
                }
                link = Link(conn, intf, ep)

                val mime = contentResolver.getType(uri) ?: ""
                when {
                    mime == "application/pdf" || name.endsWith(".pdf") -> printPdf(uri, link, o)
                    mime.startsWith("image/") || name.matches(Regex(".*\\.(jpg|jpeg|png)$")) -> printImage(uri, link, o)
                    else -> throw IOException("هذا الإصدار يطبع PDF والصور. حوّل Word إلى PDF أولاً.")
                }
                runOnUiThread { status?.text = "انتهت مهمة الإرسال. تحقق من خروج الورق من الطابعة." }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { status?.text = "الذاكرة غير كافية. جرّب جودة 300 نقطة/بوصة." }
            } catch (e: Exception) {
                runOnUiThread { status?.text = "تعذرت الطباعة: ${e.message}" }
            } finally {
                link?.let {
                    try { it.conn.releaseInterface(it.intf) } catch (_: Exception) {}
                    it.conn.close()
                }
                runOnUiThread {
                    printButton?.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun progress(page: Int, total: Int) =
        runOnUiThread { status?.text = "جارٍ طباعة الصفحة $page من $total…" }

    private fun beginJob(link: Link) {
        link.cmd("\u001b%-12345X")   // UEL: leave any previous language mode
        link.cmd("\u001bE")          // printer reset
    }

    private fun endJob(link: Link) {
        link.cmd("\u001bE")
        link.cmd("\u001b%-12345X")
    }

    private fun printPdf(uri: Uri, link: Link, o: Opts) {
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("تعذر فتح PDF")
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val total = renderer.pageCount
                if (total == 0) throw IOException("ملف PDF فارغ")
                // Multi-page: repeat the whole document so copies come out collated (1,2,3,1,2,3).
                // Single page: let the printer make the copies itself (much faster).
                val reps = if (total == 1) 1 else o.copies
                val hw = if (total == 1) o.copies else 1
                beginJob(link)
                for (rep in 0 until reps) {
                    for (index in 0 until total) {
                        progress(rep * total + index + 1, total * reps)
                        renderer.openPage(index).use { page ->
                            // page.width/height are in points (1/72 inch); the matrix passed to
                            // render() maps points to bitmap pixels.
                            sendPage(link, page.width.toFloat(), page.height.toFloat(), o, hw) { band, m ->
                                page.render(band, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            }
                        }
                    }
                }
                endJob(link)
            }
        }
    }

    private fun printImage(uri: Uri, link: Link, o: Opts) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("تعذر قراءة الصورة")

        // Decode no larger than needed for the chosen DPI (saves a lot of memory on big photos).
        val target = (11.3f * o.dpi).toInt()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("تعذر قراءة الصورة")

        // Respect the camera's EXIF orientation.
        val degrees = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val image = bmp
        beginJob(link)
        progress(1, 1)
        sendPage(link, image.width.toFloat(), image.height.toFloat(), o, o.copies) { band, m ->
            Canvas(band).drawBitmap(image, m, paint)
        }
        endJob(link)
        image.recycle()
    }

    /**
     * Prints one A4 page. Content of size [cw] x [ch] (any unit) is scaled to fit the printable
     * area (landscape automatically if wider than tall). The page is rendered and sent in bands
     * of 128 rows so memory use stays small even at 600 dpi.
     *
     * [draw] must draw the content into the given band bitmap using the given matrix, which maps
     * content coordinates to band pixels.
     */
    private fun sendPage(
        link: Link, cw: Float, ch: Float, o: Opts, pageCopies: Int, draw: (Bitmap, Matrix) -> Unit
    ) {
        val dpi = o.dpi
        val landscape = cw > ch
        val shortSide = ((8.27f - 0.4f) * dpi).roundToInt()
        val longSide = ((11.69f - 0.4f) * dpi).roundToInt()
        val boxW = if (landscape) longSide else shortSide
        val boxH = if (landscape) shortSide else longSide

        // Fit-to-page scale, then the user's shrink/enlarge factor on top of it.
        val s = minOf(boxW / cw, boxH / ch) * (o.scalePct / 100f)
        val left = (boxW - cw * s) / 2f
        val top = if (o.alignTop && ch * s <= boxH) 0f else (boxH - ch * s) / 2f

        val rowBytes = (boxW + 7) / 8
        val hdr = "\u001b*b${rowBytes}W".toByteArray(Charsets.US_ASCII)
        val bandH = 128
        val band = Bitmap.createBitmap(boxW, bandH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(boxW * bandH)
        val out = ByteArray(bandH * (hdr.size + rowBytes))

        link.cmd(
            "\u001b&l26A" +                              // paper size A4
                "\u001b&l${if (landscape) 1 else 0}O" +  // orientation
                "\u001b&l${pageCopies}X" +               // copies of this page
                "\u001b&l0E" +                           // top margin 0
                "\u001b*p0x0Y" +                         // cursor to top-left
                "\u001b*t${dpi}R" +                      // raster resolution
                "\u001b*r0F" +                           // raster follows page orientation
                "\u001b*r${boxW}S" +                     // raster width (pixels)
                "\u001b*r${boxH}T" +                     // raster height (pixels)
                "\u001b*r1A" +                           // start raster at cursor
                "\u001b*b0M"                             // uncompressed rows
        )

        try {
            var y = 0
            while (y < boxH) {
                val bh = minOf(bandH, boxH - y)
                band.eraseColor(Color.WHITE)
                val m = Matrix()
                m.postScale(s, s)
                m.postTranslate(left, top - y)
                draw(band, m)
                band.getPixels(pixels, 0, boxW, 0, 0, boxW, bh)

                var o = 0
                for (r in 0 until bh) {
                    System.arraycopy(hdr, 0, out, o, hdr.size)
                    o += hdr.size
                    java.util.Arrays.fill(out, o, o + rowBytes, 0)
                    val bayerRow = ((y + r) and 7) * 8
                    val base = r * boxW
                    for (x in 0 until boxW) {
                        val c = pixels[base + x]
                        val lum = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                        if (lum < BAYER[bayerRow + (x and 7)] * 4 + 2) {
                            val idx = o + (x shr 3)
                            out[idx] = (out[idx].toInt() or (0x80 shr (x and 7))).toByte()
                        }
                    }
                    o += rowBytes
                }
                link.write(out, o)
                y += bh
            }
        } finally {
            band.recycle()
        }
        link.cmd("\u001b*rC")   // end raster graphics
        link.cmd("\u000c")      // form feed: eject page
    }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
