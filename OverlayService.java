package com.samuel.pantallalimpia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;

public class OverlayService extends Service {

    static final String ACTION_START = "com.samuel.pantallalimpia.START";
    static final String ACTION_STOP = "com.samuel.pantallalimpia.STOP";
    static final String ACTION_RELOAD = "com.samuel.pantallalimpia.RELOAD";
    static final String ACTION_SHOW = "com.samuel.pantallalimpia.SHOW";

    static volatile boolean running = false;

    private WindowManager wm;
    private SharedPreferences prefs;
    private View boton;
    private ImageView imagen;
    private WindowManager.LayoutParams lpBoton;
    private WindowManager.LayoutParams lpImagen;
    private boolean botonPuesto = false;
    private boolean imagenPuesta = false;

    /** Decodifica la imagen reducida a un lado máximo (respeta la rotación EXIF). */
    static Bitmap cargar(File f, final int maxLado) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f),
                    new ImageDecoder.OnHeaderDecodedListener() {
                        @Override
                        public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info,
                                                    ImageDecoder.Source s) {
                            int w = info.getSize().getWidth();
                            int h = info.getSize().getHeight();
                            int m = Math.max(w, h);
                            if (m > maxLado) {
                                float k = (float) maxLado / m;
                                d.setTargetSize(Math.max(1, Math.round(w * k)),
                                        Math.max(1, Math.round(h * k)));
                            }
                        }
                    });
        } catch (Throwable e) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ponerEnPrimerPlano();
        if (!running) {
            if (!preparar()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
        }
        if (ACTION_RELOAD.equals(a)) {
            cargarImagen();
        } else if (ACTION_SHOW.equals(a)) {
            mostrarImagen();
        }
        return START_NOT_STICKY;
    }

    private void ponerEnPrimerPlano() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "pl", "Pantalla limpia", NotificationManager.IMPORTANCE_LOW));

        PendingIntent detener = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent mostrar = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_SHOW),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, "pl")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Pantalla limpia activa")
                .setContentText("Toca el botón flotante para mostrar la imagen")
                .addAction(android.R.drawable.ic_menu_view, "Mostrar imagen", mostrar)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", detener)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private boolean preparar() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        // Vista de la imagen a pantalla completa
        imagen = new ImageView(this);
        imagen.setBackgroundColor(Color.BLACK);
        imagen.setOnClickListener(v -> ocultarImagen());

        lpImagen = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lpImagen.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lpImagen.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            lpImagen.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        if (!cargarImagen()) {
            return false;
        }

        // Botón flotante pequeño y casi transparente
        boton = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x44FFFFFF);
        g.setStroke(dp(2), 0x66000000);
        boton.setBackground(g);

        lpBoton = new WindowManager.LayoutParams(
                dp(44), dp(44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lpBoton.gravity = Gravity.TOP | Gravity.START;
        lpBoton.x = prefs.getInt("bx", dp(8));
        lpBoton.y = prefs.getInt("by", dp(300));

        boton.setOnTouchListener(new View.OnTouchListener() {
            int inicioX, inicioY;
            float tocoX, tocoY;
            boolean movido;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        inicioX = lpBoton.x;
                        inicioY = lpBoton.y;
                        tocoX = e.getRawX();
                        tocoY = e.getRawY();
                        movido = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tocoX;
                        float dy = e.getRawY() - tocoY;
                        if (!movido && Math.hypot(dx, dy) > dp(8)) {
                            movido = true;
                        }
                        if (movido) {
                            lpBoton.x = (int) (inicioX + dx);
                            lpBoton.y = (int) (inicioY + dy);
                            try {
                                wm.updateViewLayout(boton, lpBoton);
                            } catch (Exception ignorada) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (movido) {
                            prefs.edit().putInt("bx", lpBoton.x).putInt("by", lpBoton.y).apply();
                        } else {
                            mostrarImagen();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(boton, lpBoton);
            botonPuesto = true;
        } catch (Exception e) {
            Toast.makeText(this, "No pude mostrar el botón. Revisa el permiso sobre otras apps.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private boolean cargarImagen() {
        Bitmap bm = cargar(new File(getFilesDir(), "fondo.img"), 2600);
        if (bm == null) {
            Toast.makeText(this, "No pude leer la imagen. Elige otra.", Toast.LENGTH_LONG).show();
            return false;
        }
        imagen.setImageBitmap(bm);
        return true;
    }

    private void mostrarImagen() {
        if (imagenPuesta || wm == null) {
            return;
        }
        try {
            imagen.setScaleType(prefs.getBoolean("fill", false)
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            wm.addView(imagen, lpImagen);
            imagenPuesta = true;
            if (botonPuesto) {
                wm.removeView(boton);
                botonPuesto = false;
            }
        } catch (Exception e) {
            Toast.makeText(this, "No pude mostrar la imagen", Toast.LENGTH_LONG).show();
        }
    }

    private void ocultarImagen() {
        try {
            if (!botonPuesto) {
                wm.addView(boton, lpBoton);
                botonPuesto = true;
            }
            if (imagenPuesta) {
                wm.removeView(imagen);
                imagenPuesta = false;
            }
        } catch (Exception e) {
            Toast.makeText(this, "No pude ocultar la imagen", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (imagenPuesta) {
                wm.removeView(imagen);
            }
        } catch (Exception ignorada) {
        }
        try {
            if (botonPuesto) {
                wm.removeView(boton);
            }
        } catch (Exception ignorada) {
        }
        imagenPuesta = false;
        botonPuesto = false;
        running = false;
        super.onDestroy();
    }
}
