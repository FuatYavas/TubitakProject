# Duygu Tanıma

## Genel Bakış
Bu proje, Android tabanlı gerçek zamanlı duygu tanıma uygulamasıdır. Projede Kotlin ve Java dilleri kullanılmış, Gradle ile yapılandırılmıştır. Uygulama, temel bir demo versiyonu olarak mobil cihazlarda duygu tanıma işlevini sunmaktadır.

## Kullanılan Model ve Yaklaşım
Projede, duygu tanıma görevleri için optimize edilmiş hafif bir derin öğrenme mimarisi kullanılmıştır:
- **Hafif Yapı:** Mobil cihazlarda minimum kaynak kullanımı sağlamak üzere optimize edilmiştir.
- **Eğitim Süreci:** Geniş verisetleri (örn. yüz ifadeleri ve ses verileri) ile eğitilerek farklı duygu durumlarının tanınması hedeflenmiştir.
- **Gerçek Zamanlı Çalışma:** Uygulama, kamera veya ses kaynağından anlık veri alarak duygu tahmini yapar.
- **Mobil Optimizasyon:** Model, TensorFlow Lite veya benzeri araçlar kullanılarak mobil ortamda çalışacak şekilde uyarlanmıştır.

## Özellikler
- Gerçek zamanlı duygu tanıma (kamera yada resim girişi ile)
- Mobil cihazlarda hızlı ve verimli çalışabilecek hafif yapıda model
- Android Studio geliştirilme

## Kullanılan Teknolojiler
- **Diller:** Kotlin, Java
- **Yapı Aracı:** Gradle (Kotlin DSL)
- **Geliştirme Ortamı:** Android Studio 

## Proje Yapısı
Projenin ana dizin yapısı aşağıdaki gibidir:
- `app/`  
  Uygulama modülü; kaynak kodların, kaynak dosyaların (XML, resimler) ve yapılandırma dosyalarının bulunduğu dizin.
  - `src/main/`  
    Uygulamanın ana kaynak dosyaları içermektedir. Örnek olarak `app/src/main/res/values/strings.xml` dosyasında uygulama adı tanımlanmıştır:
    ```xml
    <resources>
        <string name="app_name">Duygu Tanıma</string>
    </resources>
    ```
  - `src/androidTest/` ve `src/test/`  
    Test kaynakları.
- `build/`  
  Derleme çıktıları ve ara dosyaların bulunduğu dizin.
- `build.gradle.kts`  
  Projenin kök seviye Gradle yapılandırma dosyası.
- `proguard-rules.pro`  
  ProGuard yapılandırma dosyası.


