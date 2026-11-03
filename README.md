# کانکتور Provisioning برای Stalwart SCIM و WSO2 IS 7.3.0

## وضعیت نسخه

نسخه توسعه: **2.0.7-SNAPSHOT**

این نسخه در حال بازطراحی است. تا پیش از موفقیت CI و آزمون عملی روی WSO2 IS 7.3.0 و Stalwart Enterprise 1.0.2، آن را برای محیط Production تأییدشده تلقی نکنید.

## هدف و معماری

این پروژه یک Outbound Provisioning Connector مستقل برای WSO2 Identity Server است و مستقیماً با SCIM 2.0 در Stalwart ارتباط برقرار می‌کند.

- بدون Event Handler جداگانه؛
- بدون JVM Property اختصاصی و بدون Environment Variable؛
- بدون DataSource، JNDI، جدول Outbox یا صف پایدار؛
- بدون ساخت یا همگام‌سازی Group در Stalwart؛
- بدون SCIM DELETE برای حذف کاربر؛ حذف WSO2 به PATCH با `active=false` تبدیل می‌شود؛
- نام کاربری Stalwart از Claim ایمیل در زمان ایجاد گرفته می‌شود؛ `externalId` شناسه پایدار WSO2 است؛
- در Update، `userName` و `externalId` تغییر داده نمی‌شوند؛
- تغییر FIRSTNAME یا LASTNAME یا DISPLAYNAME باعث محاسبه و ارسال displayName می‌شود؛
- تغییر ایمیل اصلی، Alias یا username داخلی WSO2 به Stalwart ارسال نمی‌شود.

### حالت‌های ارسال

**INDIVIDUAL**
هر تغییر به endpoint استاندارد SCIM خودش ارسال می‌شود و Connector منتظر پاسخ می‌ماند.

**BULK**
درخواست‌های هم‌زمان برای مدت کوتاه (۱۵۰ میلی‌ثانیه) در حافظه تجمیع می‌شوند؛ همچنین با رسیدن دسته به ۱۰۰ عملیات، ارسال زودتر آغاز می‌شود. هر دسته با SCIM Bulk ارسال می‌شود و پاسخ هر Operation جداگانه به فراخواننده متناظر تحویل داده می‌شود. هر فراخوانی WSO2 تا دریافت نتیجه عملیات خودش منتظر می‌ماند.

این تجمیع فقط در حافظه است. در صورت توقف ناگهانی WSO2، عملیاتی که هنوز به Stalwart ارسال نشده‌اند ممکن است از دست بروند. در نتیجه این نسخه **تضمین تحویل پایدار یا exactly-once ندارد**. در نتیجه مبهم POST، کانکتور برای جلوگیری از تصاحب حساب، تطبیق را بر اساس externalId انجام می‌دهد و برخورد userName با externalId متفاوت را نمی‌پذیرد.

اگر Bulk با پاسخ قطعی 404، 405 یا 501 پشتیبانی نشود و fallback فعال باشد، درخواست‌های همان دسته به‌صورت Individual ارسال می‌شوند. خطای شبکه با نتیجه مبهم باعث fallback خودکار نمی‌شود.

## رفتار حساب و گروه Email

گروه WSO2 با نام پیش‌فرض `Email` شرط لازم و اجباری دسترسی به ایمیل است:
- هنگام ایجاد حساب، نبودن عضویت قطعی در گروه Email باعث می‌شود حساب با `active=false` ساخته شود؛
- اضافه‌شدن به گروه Email فقط در صورتی حساب را فعال می‌کند که Claim وضعیت اختیاری نیز حساب را غیرفعال نکرده باشد؛
- حذف از گروه Email همیشه `active=false` می‌کند؛
- Claim وضعیت اختیاری می‌تواند دسترسی را لغو کند، اما به‌تنهایی هرگز مجوز فعال‌سازی نمی‌دهد؛
- اگر رویداد فقط Claim وضعیت را داشته باشد و عضویت گروه را اثبات نکند، مقدار true آن باعث فعال‌سازی نمی‌شود؛
- حذف کاربر از WSO2 نیز به `active=false` تبدیل می‌شود.

اگر فهرست کامل گروه‌ها در رویداد اولیه موجود نباشد، کانکتور fail-closed عمل می‌کند و حساب را غیرفعال می‌سازد. برای فعال‌سازی بعدی باید رویداد افزودن به گروه Email دریافت شود. هیچ درخواست SCIM برای ایجاد، جست‌وجو، تغییر یا حذف Group در Stalwart ارسال نمی‌شود.

## نگاشت Claim پیش‌فرض

| داده WSO2 | Claim URI | ویژگی SCIM | رفتار |
|---|---|---|---|
| ایمیل اصلی | `http://wso2.org/claims/emailaddress` | `userName` | فقط زمان ایجاد؛ باید ایمیل کامل باشد |
| شناسه پایدار | `http://wso2.org/claims/username` | `externalId` | در ایجاد و برای تطبیق؛ پس از ایجاد ثابت |
| نام | `http://wso2.org/claims/givenname` | بخشی از `displayName` | با نام خانوادگی جاری از UserStore ترکیب می‌شود |
| نام خانوادگی | `http://wso2.org/claims/lastname` | بخشی از `displayName` | با نام جاری از UserStore ترکیب می‌شود |
| نام نمایشی | `http://wso2.org/claims/displayname` | `displayName` | در صورت تغییر ارسال می‌شود |
| وضعیت حساب | Claim بولی دلخواه | `active` | اختیاری؛ قابلیت معکوس‌سازی دارد |
| گروه دسترسی ایمیل | گروه WSO2، پیش‌فرض `Email` | `active` | بدون ایجاد Group در Stalwart |

رمز عبور، شماره تلفن، Alias، آدرس، عکس و Claimهای نامرتبط ارسال نمی‌شوند.

## نیازمندی‌ها

- WSO2 Identity Server 7.3.0؛
- Java 21؛
- Stalwart Enterprise 1.0.2 با SCIM فعال؛
- API Key اختصاصی با مجوزهای لازم برای ایجاد و به‌روزرسانی حساب؛
- TLS معتبر و مورد اعتماد JVM در محیط Production.

آدرس پایه نمونه:

```
https://mail.example.com/scim/v2
```

آدرس پایه نباید به `/Users`، `/Groups` یا `/Bulk` ختم شود. مسیر `/scim/v2` لازم است.

## Build

با JDK 21 و Maven:

```bash
mvn -B -ntp clean verify
```

JAR خروجی:

```
components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.7-SNAPSHOT.jar
```

فقط JAR کانکتور باید در WSO2 نصب شود؛ APIهای WSO2 داخل JAR بسته‌بندی نمی‌شوند. Gson به‌صورت dependency داخلی Bundle قرار می‌گیرد.

## نصب خودکار

در ریشه مخزن:

```bash
chmod +x install.sh uninstall.sh
./install.sh /path/to/org.stalwart.identity.provisioning.connector.scim-2.0.7-SNAPSHOT.jar
```

نصب‌کننده ابتدا VM یا Docker را می‌پرسد؛ در حالت Docker نام Container را می‌گیرد. مسیر dropins و مسیر TOML را تشخیص داده و پیش از هر تغییری تأیید می‌خواهد. JAR قبلی همین Bundle با پسوند backup نگهداری می‌شود. فایل‌های دیگر حذف یا جایگزین نمی‌شوند. برای حذف، `./uninstall.sh` را اجرا کنید؛ اسکریپت JAR را به فایل disabled منتقل می‌کند و TOML را نگه می‌دارد.

## نصب دستی

1. WSO2 را متوقف کنید.
2. JAR نسخه 2.0.7 را در مسیر زیر کپی کنید:

   `<WSO2_HOME>/repository/components/dropins/`

3. JAR قدیمی همین کانکتور را از dropins خارج کنید؛ فایل‌های نامرتبط را حذف نکنید.
4. WSO2 را راه‌اندازی کنید.
5. لاگ را برای فعال‌شدن OSGi Component و ثبت Factory بررسی کنید.
6. در پنل WSO2 یک Outbound Provisioning Connector از نوع `stalwart-scim` بسازید و فیلدهای اتصال و Claim را تنظیم کنید.

تنظیمات اتصال را می‌توان از فایل زیر نیز خواند:

`<WSO2_HOME>/repository/conf/stalwart-scim-connector.toml`

این فایل در بخش `[stalwart-scim]` قرار می‌گیرد و کلیدهای آن همان نام Propertyهای کانکتور هستند؛ برای نمونه `scim-base-url` و `scim-bearer-token`. مقادیر TOML پس از Propertyهای UI اعمال می‌شوند و بنابراین بر آن‌ها اولویت دارند. در صورت نبود فایل، تنظیمات UI بدون تغییر استفاده می‌شوند. مسیر از Property استاندارد `carbon.home` در WSO2 به دست می‌آید و JVM Property یا Environment Variable اختصاصی لازم نیست.

قالب نمونه در `config/stalwart-scim-connector.toml.example` قرار دارد. نصب‌کننده فایل را در صورت نبودن ایجاد می‌کند؛ اگر فایل یا بخش آن از قبل وجود داشته باشد، محتوای قبلی را بازنویسی نمی‌کند. برای نگهداری Token، مجوز فایل را فقط به کاربر WSO2 محدود کنید.

## نمونه فایل TOML

```toml
[stalwart-scim]
scim-base-url = "https://mail.example.com/scim/v2"
scim-bearer-token = "REPLACE_WITH_API_KEY"
provisioning-mode = "BULK"
bulk-request-timeout-ms = 30000
bulk-max-payload-bytes = 900000
max-concurrent-requests = 8
email-membership-group = "Email"
```

سایر کلیدهای اختیاری در فایل نمونه موجودند. رشته‌ها باید داخل کوتیشن دوتایی باشند؛ Booleanها به‌صورت `true` یا `false` و اعداد به‌صورت عدد صحیح نوشته شوند. Parser عمداً فقط این scalarها را می‌پذیرد و آرایه، inline table یا multiline string را پشتیبانی نمی‌کند.

## نکات امنیتی و عملیاتی

- در محیط غیرآزمایشی فقط HTTPS استفاده کنید. HTTP فقط برای localhost و آزمون محلی پذیرفته می‌شود.
- Token در لاگ چاپ نمی‌شود.
- جست‌وجوی کاربر با SCIM `POST /Users/.search` انجام می‌شود تا ایمیل و شناسه‌های کاربر در query string و لاگ‌های URL قرار نگیرند.
- آدرس جایگزین Profile Update باید هم‌مبدأ (scheme/host/port یکسان) با آدرس اصلی باشد؛ Token به میزبان دیگر ارسال نمی‌شود.
- کلید ناشناخته در بخش `[stalwart-scim]` باعث خطای راه‌اندازی می‌شود تا غلط املایی تنظیمات بی‌صدا نادیده گرفته نشود.
- Cache شناسه‌ها و دسته‌بندی Bulk به Tenant و User Store Domain محدود شده‌اند.
- از TLS verification صرف‌نظر نکنید.
- دسته‌های Bulk در حافظه نگهداری می‌شوند و durable نیستند؛ این رفتار با طراحی بدون Outbox سازگار است، اما در برابر crash تضمین بازیابی ندارد.
- پاسخ‌های Bulk باید شامل یک نتیجه برای هر Operation باشند؛ نتیجه ناقص به‌عنوان موفقیت تلقی نمی‌شود.
- خطای هر Operation جداگانه به فراخوانی‌کننده همان عملیات گزارش می‌شود.

## آزمون‌های پذیرش لازم

CI ساخت Maven، تست Factory، تجمیع درخواست‌های هم‌زمان در Bulk، تبدیل DELETE به PATCH active=false، سیاست fail-closed گروه Email، بی‌اثر بودن Active Claim مثبت بدون عضویت Email، جست‌وجوی POST و پاک‌کردن displayName را بررسی می‌کند. علاوه بر آن، قبل از Production این موارد روی WSO2 IS 7.3.0 و Stalwart Enterprise 1.0.2 واقعی آزمایش شوند:

1. فعال‌شدن Bundle و تزریق RealmService در OSGi؛
2. خواندن Claimهای جاری FIRSTNAME/LASTNAME برای UserStore اصلی و ثانویه؛
3. ایجاد کاربر و تطبیق externalId؛
4. تغییر نام و نام خانوادگی و displayName؛
5. حذف کاربر و حذف از گروه Email؛
6. فعال‌سازی مجدد با افزودن به گروه Email؛
7. ارسال Bulk با پاسخ‌های ترکیبی موفق/ناموفق؛
8. قطع شبکه حین Bulk و بررسی رفتار نتیجه مبهم؛
9. آزمون بار با تعداد زیاد Provisioning هم‌زمان.

## مجوز

Apache License 2.0
