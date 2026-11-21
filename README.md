# Omni Float AI

Aplikasi Android 8.0+ untuk chat teks melalui jendela utama dan bubble yang dapat digeser.

## Pakai aplikasi
1. Ketuk API dan pilih OpenAI, Gemini, Groq, DeepSeek, OpenRouter, atau Custom.
2. Masukkan API key dan tunggu katalog model dimuat. Pilih Otomatis atau cari ID model yang tersedia lalu ketuk Simpan & pakai. Custom menerima URL HTTPS /chat/completions, katalog /models di prefix yang sama, dan Bearer token opsional.
3. Ketuk Aktifkan bubble dan izinkan aplikasi tampil di atas aplikasi lain. Ketuk bubble untuk chat atau geser untuk memindahkannya.
4. Ketuk Minimalkan untuk kembali ke bubble. Tahan bubble, pilih Matikan dari notifikasi, atau ketuk Matikan di layar utama untuk menghentikannya.

API key disimpan dengan AES-GCM melalui Android Keystore. Pesan dikirim ke endpoint profil aktif. Riwayat berlaku untuk sesi aktif dan direset setiap kali konfigurasi disimpan.

## Build APK
- Lokal: buka folder proyek di Android Studio, gunakan JDK 17, pasang Android SDK Platform 35, sinkronkan Gradle 8.9, lalu pilih Build > Build App Bundle(s) / APK(s) > Build APK(s). Dengan Gradle 8.9 pada PATH, jalankan `gradle :app:assembleDebug`. Hasil ada di `app/build/outputs/apk/debug/app-debug.apk`.
- GitHub: masukkan isi folder proyek termasuk folder tersembunyi .github ke repositori dengan Actions aktif. Jalankan workflow Build Android APK. Unduh artefak Omni-Float-AI-APK setelah build berhasil dan ekstrak APK-nya.



## Floating v1.3
Terjemah layar membutuhkan izin Android dan model yang menerima gambar. Teks Indonesia ditumpuk di posisi perkiraan teks asli selama 20 detik. Pilih opsi kuis hanya membaca tombol radio yang diekspos oleh halaman kuis Chrome yang didukung. Fitur kuis perlu diaktifkan di setelan aksesibilitas Android. Pengiriman final ditinjau dan dilakukan oleh pengguna.


Pada v1.5, ketuk Aktifkan Terjemah Layar dan setujui akses layar Android. Geser halaman seperti biasa. Buka bubble dan ketuk tombol yang sama untuk mematikan. Pengenalan teks beraksara Latin dan terjemahan memakai ML Kit di perangkat; unduhan model bahasa pertama memerlukan internet.

