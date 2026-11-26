# نقل مفتاح التوقيع إلى GitHub Secrets

المفتاح نفسه (debug.keystore) لازم يفضل هو هو، عشان التحديثات تتثبت فوق النسخة القديمة.
المفتاح ما يتحطش في المستودع أبداً (الملف .gitignore بيمنعه). بيتخزن كـ Secrets بس.

## 1) جهّز قيمة المفتاح (في Termux على موبايلك)
```
base64 -w0 ~/H-DO-3/debug.keystore
```
انسخ الناتج كله (سطر واحد طويل). لو الملف مش هناك دور على debug.keystore في Downloads.

## 2) أضف 4 Secrets في المستودع
GitHub ← المستودع ← Settings ← Secrets and variables ← Actions ← New repository secret

| الاسم | القيمة |
|---|---|
| HDO_KEYSTORE_B64 | الناتج الطويل من الخطوة 1 |
| HDO_KEYSTORE_PASSWORD | android |
| HDO_KEY_ALIAS | androiddebugkey |
| HDO_KEY_PASSWORD | android |

## 3) انشر نسخة
Actions ← Release signed APK ← Run workflow ← اكتب رقم النسخة (مثلاً 1.5.6، لازم يطابق versionName) ← Run.
بعد دقائق يظهر Release جديد فيه ملفين APK، وزر «التحقق من التحديثات» في التطبيق بيقرأ منه.

## ملاحظات
- ملف .github/workflows/release.yml لازم ترفعه من الموقع أو بـ PAT فيه صلاحية workflow.
- بعد ما تتأكد إن البناء اشتغل، امسح أي نسخة من debug.keystore محطوطة في المستودع أو في Downloads عامة.
