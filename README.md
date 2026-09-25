# Redmi BatteryDiag v1.2

## Yeni özellikler

- Shizuku gelişmiş erişim: Normal APK'nin okuyamadığı Xiaomi / MediaTek `/sys/class/power_supply` alanlarını ADB-shell kimliğiyle read-only okumayı dener.
- Şarj Testi: 5 saniyede bir SOC, V, A, W, sıcaklık, protokol, USB alanları ve charge counter kaydı.
- Test özeti: başlangıç/bitiş SOC, süre, max/ortalama güç, max/ortalama sıcaklık, 40 C üstü süre ve mümkünse charge-counter farkı.
- 5 grafik: SOC, gerilim, akım, güç ve sıcaklık.
- CSV dışa aktarma.
- Kalıcı APK imzası için GitHub Actions desteği.
- `v*` tag'i ile signed build varsa GitHub Release oluşturma desteği.

## Repo'da değiştirilecek dosyalar

Mevcut dosyaları bunlarla değiştir:

- `build.gradle`
- `settings.gradle`
- `gradle.properties`
- `.github/workflows/build-apk.yml`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/oai/redmibatterydiag/MainActivity.java`
- `app/src/main/res/drawable/ic_battery_diag.xml`

Yeni oluşturulacak dosyalar:

- `app/src/main/java/com/oai/redmibatterydiag/BatteryShellService.java`
- `app/src/main/aidl/com/oai/redmibatterydiag/IBatteryShellService.aidl`

## Shizuku kullanımı

1. Shizuku'yu telefona kur.
2. Android 11+ cihazda Shizuku içinden Wireless Debugging ile Shizuku servisini başlat.
3. Redmi BatteryDiag'i aç.
4. `Shizuku izni ver` düğmesine bas ve izni onayla.
5. Durum `Bağlı - Gelişmiş sysfs erişimi - ADB shell` olduğunda uygulama normal erişimde başarısız olan MU/sysfs alanlarını shell kimliğiyle tekrar dener.

Not: Shizuku root değildir. Xiaomi/HyperOS SELinux bir alanı ADB shell için de kapatmışsa o alan yine okunamayabilir.

## Kalıcı imza - bir kere yapılacak

`RedmiBatteryDiag-v1.2-signing-private.zip` dosyasındaki `GITHUB_SECRETS.txt` içindeki dört değeri GitHub'da:

`Repository -> Settings -> Secrets and variables -> Actions -> New repository secret`

altına tek tek ekle:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`.jks` dosyasını ve `GITHUB_SECRETS.txt` dosyasını GitHub reposuna ASLA yükleme. Bunlar sadece yedek içindir.

İlk signed v1.2, daha önce debug key ile kurulmuş v1.1'in üstüne kurulamaz. v1.1'i bir kez kaldırıp signed v1.2'yi kur. Bundan sonraki aynı anahtarla imzalı sürümler normal güncelleme gibi üstüne kurulabilir.

## Build

Commit'ten sonra Actions otomatik çalışır. Signing secrets ayarlıysa artifact:

`RedmiBatteryDiag-v1.2.apk`

olarak signed release APK üretir. Secrets ayarlı değilse debug APK üretir.

Bir GitHub Release oluşturmak istersen repo'da `v1.2` tag'i oluştur. Workflow signed APK'yı release'e ekler.
