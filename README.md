# راهنمای فارسی کانکتور WSO2 Identity Server به Stalwart با SCIM 2.0

- **نسخه توسعه:** 2.0.5-SNAPSHOT
- **شاخه:** feature/2.0.5
- **نسخه هدف WSO2:** Identity Server 7.3.0
- **مقصد:** Stalwart Enterprise با قابلیت SCIM 2.0
- **نوع اتصال:** Outbound Provisioning Connector
- **احراز هویت:** Bearer Token
- **بسته قابل نصب:** فقط یک فایل JAR

> نسخه 2.0.5 برای حذف وابستگی به Event Handler جداگانه و تنظیمات دستی JVM طراحی شده است. نصب این نسخه به یک JAR و تنظیمات UI اتصال نیاز دارد. صف پایدار از دیتابیس استاندارد WSO2 با نام JNDI برابر jdbc/WSO2CarbonDB استفاده می‌کند.
>
> [مطالعه سند طراحی فنی و جزئیات Outbox نسخه 2.0.5](docs/2.0.5-ARCHITECTURE.md)

---

## ۱. تغییرات مهم نسخه 2.0.5

1. **حذف Event Handler جداگانه:** دیگر نیازی به ثبت رویدادهای POST_ADD_USER یا POST_SET_USER_CLAIMS در deployment.toml نیست.
2. **حذف JVM Propertyها:** هیچ تنظیمی با پیشوند stalwart.scim.hybrid لازم نیست.
3. **یک JAR قابل نصب:** Connector، منطق خواندن Claimهای جاری WSO2 و Outbox در همان Bundle قرار دارند.
4. **صف پایدار برای BULK:** پیش از ارسال هر عملیات تغییردهنده به Stalwart، درخواست در جدول JDBC ثبت و Commit می‌شود.
5. **بازیابی پس از راه‌اندازی مجدد:** عملیات ثبت‌شده و تأییدنشده پس از restart شدن WSO2 یا خاموش و روشن شدن سرور از دیتابیس بازیابی می‌شوند.
6. **Bulk چندعملیاتی:** Worker چند عملیات صف‌شده را در یک درخواست SCIM Bulk قرار می‌دهد و نتیجه هر عملیات را جداگانه ثبت می‌کند.
7. **بازیابی خودکار خطاها:** خطاهای موقت با backoff مجدداً تلاش می‌شوند؛ عملیات دائماً ناموفق به وضعیت DEAD می‌رود و برای بررسی باقی می‌ماند.
8. **خواندن Claim کامل بدون Handler:** برای تغییر FIRSTNAME یا LASTNAME، کانکتور با RealmService داخلی WSO2، مقادیر جاری Claimها را از UserStore می‌خواند.
9. **حفظ قرارداد هویت:** تغییر ایمیل اصلی، Aliasها و username داخلی WSO2 به Stalwart ارسال نمی‌شود.

## ۲. معماری و حالت‌های اجرا

### INDIVIDUAL

هر عملیات تغییردهنده بلافاصله با endpoint استاندارد SCIM اجرا می‌شود. پاسخ Stalwart به WSO2 برگردانده می‌شود. در این حالت صف پایدار استفاده نمی‌شود.

### BULK

هر عملیات ابتدا در جدول STALWART_SCIM_OPERATION_OUTBOX در دیتابیس WSO2 ذخیره می‌شود. پس از Commit شدن رکورد، کانکتور تلاش می‌کند آن را فوراً ارسال کند. علاوه بر این، Worker پس‌زمینه هر ۱۵ ثانیه صف را بررسی می‌کند و حداکثر ۲۵ عملیات را در یک درخواست Bulk جمع می‌کند.

اگر WSO2 یا سرور خاموش شود، رکوردهای Pending و In-Flight از بین نمی‌روند. پس از راه‌اندازی مجدد و مقداردهی مجدد اتصال، Worker پردازش را ادامه می‌دهد. رکوردهای In-Flight پس از پایان مهلت Lease دوباره قابل دریافت می‌شوند.

اگر عملیات در مهلت درخواست به تأیید Stalwart نرسد، WSO2 خطای Provisioning دریافت می‌کند؛ اما رکورد در صف باقی می‌ماند و Worker به تلاش خود ادامه می‌دهد. بنابراین «ثبت موفق در صف» با «اعمال قطعی در Stalwart» یکسان نیست.

اگر ایجاد کاربر در BULK هنوز از صف به Stalwart نرسیده باشد، مراحل بعدی همان callback مانند اعمال عضویت اولیه گروه اجرا نمی‌شوند؛ چون کانکتور ابتدا منتظر نتیجه ایجاد User می‌ماند. اگر WSO2 رویداد را خودکار تکرار نکند، پس از ایجاد کاربر توسط Worker باید Provisioning همان کاربر دوباره trigger شود تا عضویت گروه‌ها نیز اعمال شود.

### HYBRID

- ایجاد کاربر: فوری و از طریق Individual POST.
- تغییر FIRSTNAME، LASTNAME یا DISPLAYNAME: ثبت در صف پایدار و ارسال Bulk چندعملیاتی هر پنج دقیقه.
- تغییر وضعیت فعال و عضویت گروه‌ها: فوری و از طریق Individual.
- خواندن تصویر کامل FIRSTNAME و LASTNAME: از UserStore داخلی WSO2 و از طریق RealmService؛ بدون Event Handler.

### تفاوت PATCH و Bulk

PATCH روش تغییر یک منبع SCIM است. Bulk روش انتقال چند عملیات SCIM در یک درخواست HTTP است. یک عملیات PATCH می‌تواند در حالت INDIVIDUAL از endpoint کاربر ارسال شود یا در حالت BULK/HYBRID داخل آرایه Operations درخواست Bulk قرار گیرد.

| حالت | ایجاد کاربر | تغییر نام | active و عضویت گروه | حذف کاربر | صف پایدار |
|---|---|---|---|---|---|
| INDIVIDUAL | POST فوری | PATCH فوری | PATCH فوری | DELETE فوری | ندارد |
| BULK | Bulk POST | Bulk PATCH | Bulk PATCH | Bulk DELETE | همه عملیات تغییردهنده |
| HYBRID | POST فوری | Bulk PATCH هر ۵ دقیقه | PATCH فوری | DELETE فوری | فقط تغییرات نام |

---

## ۳. جدول رفتار نهایی نرم‌افزار

این جدول قرارداد قطعی نسخه 2.0.5 است. در ردیف‌هایی که «هیچ» نوشته شده، هیچ درخواست GET/POST/PATCH/DELETE به Stalwart ارسال نمی‌شود و رکوردی هم در صف ثبت نمی‌شود.

| رویداد در WSO2 | رفتار Stalwart | روش و زمان | توضیح |
|---|---|---|---|
| ایجاد کاربر جدید | ایجاد User | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | ایمیل کامل برای userName اولیه و externalId پایدار لازم است. |
| تغییر FIRSTNAME | تغییر displayName | فوری در INDIVIDUAL؛ صف و Bulk در BULK؛ صف پنج‌دقیقه‌ای در HYBRID | تغییر هر یک از سه Claim نام کافی است؛ شرط OR است. |
| تغییر LASTNAME | تغییر displayName | مطابق حالت اجرا | تصویر کامل نام از UserStore خوانده می‌شود. |
| تغییر DISPLAYNAME | تغییر displayName | مطابق حالت اجرا | مقدار جاری DISPLAYNAME اولویت دارد. |
| تغییر هم‌زمان چند Claim نام | یک displayName نهایی | مطابق حالت اجرا | از ارسال PATCHهای متعارض جلوگیری می‌شود. |
| تغییر آدرس اصلی ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | userName در Stalwart تغییر نمی‌کند. |
| تغییر Alias ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | Alias همگام‌سازی نمی‌شود. |
| اضافه‌کردن Alias ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | Alias جدید ایجاد نمی‌شود. |
| حذف Alias ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | Alias حذف نمی‌شود. |
| تغییر username داخلی WSO2 | هیچ تغییری | هیچ درخواست و هیچ صفی | externalId موجود ثابت می‌ماند. |
| افزودن به گروه Email | active=true | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | گروه Email در Stalwart ساخته نمی‌شود. |
| حذف از گروه Email | active=false | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | دسترسی ایمیل غیرفعال می‌شود. |
| تغییر Claim وضعیت حساب | تغییر active | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | فقط Claim بولی تنظیم‌شده بررسی می‌شود. |
| افزودن به گروه توزیع دیگر | افزودن عضو به Group | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | گروه باید از قبل در Stalwart وجود داشته باشد. |
| حذف عضویت گروه توزیع دیگر | حذف عضو از Group | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | فقط تغییر عضویت اعمال می‌شود. |
| ساخت/ویرایش/حذف خود گروه WSO2 | هیچ تغییر گروهی | هیچ درخواست گروهی | چرخه عمر Groupهای Stalwart مستقل است. |
| حذف کاربر از WSO2 | DELETE User | فوری در INDIVIDUAL/HYBRID؛ صف و Bulk در BULK | حذف با غیرفعال‌سازی تفاوت دارد. |
| تغییر رمز عبور WSO2 | هیچ تغییری | هیچ درخواست SCIM | رمز عبور provision نمی‌شود. |

### قواعد ثابت هویت

- externalId شناسه پایدار اتصال دو سامانه است.
- userName فقط هنگام ایجاد اولیه از Claim ایمیل اصلی گرفته می‌شود.
- در هیچ Update مقدار userName یا externalId تغییر نمی‌کند.
- تطبیق حساب موجود با externalId انجام می‌شود؛ تطبیق صرف با ایمیل برای تصاحب حساب مجاز نیست.
- برخورد userName با حسابی که externalId متفاوت دارد، خطای تعارض است و کانکتور آن حساب را تصاحب نمی‌کند.

---

## ۴. صف پایدار BULK

### محل ذخیره‌سازی

نام جدول:

STALWART_SCIM_OPERATION_OUTBOX

نام JNDI پیش‌فرض:

jdbc/WSO2CarbonDB

کانکتور این DataSource استاندارد WSO2 را به‌صورت خودکار پیدا می‌کند و جدول و Index موردنیاز را در اولین مقداردهی ایجاد می‌کند. نیازی به تعریف متغیر محیطی، JVM Property، فایل تنظیمات جداگانه یا افزودن DataSource جدید نیست.

### چرخه عمر یک عملیات

1. WSO2 رویداد Provisioning را به Connector تحویل می‌دهد.
2. کانکتور درخواست SCIM را با Method، Path و Payload نهایی آماده می‌کند.
3. عملیات در Outbox ذخیره و تراکنش Commit می‌شود.
4. کانکتور برای کاهش تأخیر، فوراً تلاش می‌کند صف را تخلیه کند.
5. Worker پس‌زمینه نیز هر ۱۵ ثانیه عملیات Pending را بررسی می‌کند.
6. عملیات‌ها بر اساس مقصد دسته‌بندی و در Bulk چندعملیاتی ارسال می‌شوند.
7. پاسخ هر Operation جداگانه ارزیابی می‌شود.
8. عملیات موفق به SENT می‌رود؛ عملیات موقتاً ناموفق دوباره زمان‌بندی می‌شود؛ خطاهای دائمی یا رسیدن به سقف تلاش به DEAD می‌روند.

### وضعیت‌های رکورد

| وضعیت | معنی |
|---|---|
| PENDING | در صف و آماده ارسال یا تلاش مجدد |
| IN_FLIGHT | توسط یک Worker دریافت شده و Lease فعال دارد |
| SENT | Stalwart عملیات را موفق اعلام کرده است |
| DEAD | تعداد تلاش‌ها تمام شده یا خطای غیرقابل‌تکرار رخ داده است |

- حداکثر ۲۵ عملیات در هر نوبت دریافت می‌شود.
- اندازه درخواست پیش از ارسال کنترل می‌شود.
- Lease عملیات پس از crash یا توقف Worker منقضی می‌شود تا Worker بعدی بتواند آن را بازیابی کند.
- تلاش مجدد با backoff افزایشی انجام می‌شود.
- حداکثر ۲۰ تلاش برای عملیات ناموفق انجام می‌شود.
- رکوردهای SENT تا ۳۰ روز نگهداری و سپس پاک‌سازی می‌شوند.
- رکوردهای DEAD برای بررسی اپراتور باقی می‌مانند.
- Bearer Token و رمز عبور در Outbox ذخیره نمی‌شوند؛ Payloadهای Provisioning ممکن است حاوی اطلاعات پروفایل باشند و باید از دیتابیس WSO2 محافظت شود.

### ماندگاری در VM و Docker

در نصب مستقیم روی VM، دیتابیس WSO2 باید روی دیسک پایدار باقی بماند. اگر از دیتابیس H2 پیش‌فرض استفاده می‌کنید، فایل‌های دیتابیس در مسیر دیتابیس نصب WSO2 نگهداری می‌شوند.

در Docker، حذف و ساخت مجدد Container نباید باعث حذف دیتابیس WSO2 شود. مسیر دیتابیس WSO2 را روی Volume پایدار نگه دارید. اگر دیتابیس یا Volume حذف شود، هیچ نرم‌افزاری نمی‌تواند رکوردهای صف را بازیابی کند.

در نصب چندنودی، همه نودها باید به یک DataSource مشترک و پایدار متصل باشند. دیتابیس محلی مستقل هر نود، صف مشترک کلاستر محسوب نمی‌شود.

### رفتار در خطا

- پاسخ قطعی 404/405/501 از endpoint Bulk، در صورت فعال بودن fallback، باعث ارسال همان رکوردهای ذخیره‌شده از endpointهای Individual می‌شود.
- خطای شبکه با نتیجه نامشخص باعث حذف رکورد نمی‌شود.
- POST کاربر پس از نتیجه مبهم با externalId تطبیق داده می‌شود تا از ایجاد حساب تکراری جلوگیری شود.
- PATCHهای replace و DELETEهای تکراری با سیاست idempotent مدیریت می‌شوند.
- موفقیت HTTP کلی Bulk به‌تنهایی کافی نیست؛ نتیجه تک‌تک Operations بررسی می‌شود.

---

## ۵. منطق نام نمایشی بدون Event Handler

کانکتور برای تغییر نام به رویدادهای WSO2 Event Framework وابسته نیست. خود Outbound Connector در زمان دریافت Provisioning، Claimهای جاری را از UserStore می‌خواند.

شرط تشخیص تغییر نام:

FIRSTNAME changed OR LASTNAME changed OR DISPLAYNAME changed

- اگر FIRSTNAME یا LASTNAME تغییر کرده باشد، کانکتور هر دو مقدار جاری را از UserStore می‌خواند و displayName را می‌سازد.
- اگر DISPLAYNAME تغییر کرده باشد، مقدار جاری آن برای displayName استفاده می‌شود.
- اگر تصویر کامل Claimها قابل دریافت نباشد، مقدار ناقص یا قدیمی ارسال نمی‌شود و خطای مشخص ثبت می‌شود.
- در HYBRID، مقدار نهایی در Outbox پایدار ثبت و هر پنج دقیقه با Bulk PATCH ارسال می‌شود.
- این خواندن از طریق RealmService استاندارد WSO2 انجام می‌شود؛ هیچ تنظیم دستی JVM یا Event Handler لازم نیست.

---

## ۶. نگاشت Claimها

| ویژگی | Claim پیش‌فرض WSO2 | مقصد Stalwart | سیاست |
|---|---|---|---|
| ایمیل اصلی | http://wso2.org/claims/emailaddress | userName | فقط هنگام ایجاد |
| شناسه داخلی | http://wso2.org/claims/username | externalId | شناسه پایدار؛ پس از ایجاد ثابت |
| نام | http://wso2.org/claims/givenname | ورودی displayName | تغییر آن باعث به‌روزرسانی نام می‌شود |
| نام خانوادگی | http://wso2.org/claims/lastname | ورودی displayName | تغییر آن باعث به‌روزرسانی نام می‌شود |
| نام نمایشی | http://wso2.org/claims/displayname | displayName | مقدار جاری در صورت تغییر ارسال می‌شود |
| وضعیت حساب | Claim بولی دلخواه | active | اختیاری |
| عضویت Email | گروه WSO2 با نام Email | active | گروه SCIM ساخته نمی‌شود |

در ایجاد User فقط فیلدهای موردنیاز ارسال می‌شوند: schema اصلی User، userName معتبر، externalId پایدار، displayName در صورت وجود و active در صورت پیکربندی. رمز عبور، تلفن، آدرس، عکس، Alias، گروه‌ها و Claimهای نامرتبط ارسال نمی‌شوند.

---

## ۷. پیش‌نیازهای Stalwart

1. Stalwart Enterprise با مجوز معتبر و قابلیت SCIM فعال باشد.
2. دامنه ایمیل موردنظر از قبل در Stalwart ساخته شده باشد.
3. اجازه SCIM Provisioning برای دامنه فعال باشد.
4. یک حساب اختصاصی Provisioning با مجوزهای لازم ایجاد شود.
5. برای حساب Provisioning یک API Key بسازید.
6. گواهی TLS مقصد توسط JVM مورد استفاده WSO2 مورد اعتماد باشد.
7. WSO2 به endpoint SCIM مقصد دسترسی شبکه داشته باشد.

آدرس پایه نمونه:

https://mail.example.com/scim/v2

آدرس پایه باید به /scim/v2 ختم شود. مسیرهای /Users، /Groups و /Bulk را به انتهای مقدار UI اضافه نکنید.

### آزمون اولیه اتصال

~~~bash
curl -i https://mail.example.com/scim/v2/ServiceProviderConfig
~~~

آزمون احراز هویت:

~~~bash
curl -i \
  -H "Authorization: Bearer YOUR_SCIM_TOKEN" \
  -H "Accept: application/scim+json" \
  "https://mail.example.com/scim/v2/Users?count=1"
~~~

اگر خطای 401 دریافت شد، Token و مجوزهای حساب Provisioning را بررسی کنید. اگر خطای domain does not exist دریافت شد، دامنه و tenant حساب Provisioning در Stalwart را بررسی کنید.

---

## ۸. تنظیمات UI در WSO2

در WSO2 IS 7.3.0 یک Outbound Provisioning Connection از نوع stalwart-scim ایجاد کنید.

| فیلد UI | مقدار پیش‌فرض | توضیح |
|---|---|---|
| Stalwart SCIM Base URL | خالی/نمونه در UI | آدرس پایه با انتهای /scim/v2 |
| Stalwart SCIM Bearer Token | خالی | API Key حساب Provisioning در Stalwart |
| User Store Domain | خالی | دامنه UserStore در WSO2، در صورت نیاز |
| Enable Claim Mapping | true | استفاده از نگاشت Claimهای WSO2 |
| userName source WSO2 local claim URI | emailaddress | Claim ایمیل اصلی؛ فقط برای ایجاد |
| Required immutable externalId source WSO2 local claim URI | username | Claim شناسه پایدار |
| name.givenName source WSO2 local claim URI | givenname | Claim نام |
| name.familyName source WSO2 local claim URI | lastname | Claim نام خانوادگی |
| displayName source WSO2 local claim URI | displayname | Claim نام نمایشی |
| Enable Profile Updates | true | فعال‌بودن همگام‌سازی نام |
| Profile Update SCIM URL | خالی | endpoint جایگزین اختیاری؛ در حالت عادی خالی |
| Maximum Concurrent SCIM Requests | 8 | حداکثر درخواست هم‌زمان |
| active source WSO2 local Boolean claim URI | خالی | Claim اختیاری وضعیت حساب |
| Invert active source value | false | معکوس‌کردن Claimهایی مانند accountDisabled |
| WSO2 group that grants email access | Email | گروه مجوزدهی ایمیل |
| Provisioning Mode | INDIVIDUAL | یکی از INDIVIDUAL، BULK یا HYBRID |
| Fallback to Individual if Bulk endpoint is unavailable | true | fallback فقط در پاسخ قطعی عدم پشتیبانی Bulk |
| Bulk request timeout | 30000 ms | مهلت درخواست Bulk |
| Bulk maximum payload size | 900000 bytes | سقف بدنه درخواست Bulk |

تنظیمات Outbox، JNDI، Scope و زمان‌بندی در UI نمایش داده نمی‌شوند؛ کانکتور از DataSource استاندارد WSO2 استفاده می‌کند.

### پیشنهاد انتخاب حالت

- اگر اجرای هم‌زمان و فوری برایتان اولویت دارد و صف پایدار لازم ندارید: INDIVIDUAL.
- اگر می‌خواهید همه تغییرات در صف پایدار ثبت شوند و با Bulk چندعملیاتی ارسال شوند: BULK.
- اگر ایجاد کاربر و تغییر وضعیت/گروه فوری باشد ولی تغییر نام هر پنج دقیقه تجمیع شود: HYBRID.

---

## ۹. نصب نسخه 2.0.5 روی WSO2 IS 7.3.0

**نکته ارتقا از 2.0.4 در حالت HYBRID:** اگر صف پروفایل نسخه قبلی رکوردهای Pending دارد، کانکتور 2.0.5 در صورت باقی‌ماندن مقدار قدیمی hybrid-scope-id در تنظیمات ذخیره‌شده اتصال، آن Scope را نیز تخلیه می‌کند. پیش از ارتقا مطمئن شوید این Property قدیمی در تنظیمات اتصال حفظ شده است؛ اگر حذف شده، جدول قدیمی STALWART_SCIM_PROFILE_OUTBOX را بررسی کنید و رکوردهای Pending را پیش از تغییر Scope تعیین تکلیف کنید.

### نصب جدید یا ارتقا از 2.0.4

1. فایل JAR نسخه 2.0.5 را از Artifact مربوط به GitHub Actions دریافت کنید.
2. فقط همین JAR را در مسیر زیر قرار دهید:

   <WSO2_HOME>/repository/components/dropins/

3. اگر نسخه 2.0.4 را نصب کرده‌اید، JAR جداگانه Event Handler نسخه قبلی را از dropins خارج کنید.
4. اگر قبلاً بخش Event Handler مربوط به Stalwart را به deployment.toml اضافه کرده‌اید، آن بخش را حذف کنید.
5. JVM Propertyهای قبلی با پیشوند stalwart.scim.hybrid دیگر موردنیاز نیستند و می‌توانید آن‌ها را از تنظیمات راه‌اندازی حذف کنید.
6. WSO2 را restart کنید.
7. لاگ را بررسی کنید تا پیام فعال‌شدن Connector و ثبت Factory نمایش داده شود.
8. در UI یک اتصال جدید یا اتصال موجود را با نوع stalwart-scim پیکربندی کنید.
9. حالت Provisioning موردنظر را انتخاب و اتصال را فعال کنید.

**در نسخه 2.0.5 هیچ JAR جداگانه‌ای برای Event Handler وجود ندارد و نباید نصب شود.**

### Build از Source

پیش‌نیازها:
- JDK 21
- Apache Maven
- دسترسی به مخازن Maven عمومی WSO2

ساخت:

~~~bash
mvn -B -ntp clean verify
~~~

JAR قابل نصب در مسیر زیر تولید می‌شود:

components/org.stalwart.identity.provisioning.connector.scim/target/org.stalwart.identity.provisioning.connector.scim-2.0.5-SNAPSHOT.jar

فقط همین JAR را در WSO2 نصب کنید.

---

## ۱۰. امنیت و نگهداری

- از HTTPS استفاده کنید و اعتبار گواهی TLS را غیرفعال نکنید.
- Bearer Token در Outbox ذخیره نمی‌شود.
- Payloadهای صف ممکن است شامل نام یا سایر اطلاعات پروفایل باشند؛ دسترسی به دیتابیس WSO2 را محدود کنید.
- دسترسی به جدول Outbox را فقط برای حساب دیتابیس WSO2 و مدیران مجاز فراهم کنید.
- از ثبت Token، رمز عبور یا کل Claimهای کاربر در لاگ خودداری می‌شود.
- دیتابیس WSO2 و Volume آن باید روی ذخیره‌ساز پایدار باشند.
- در محیط کلاستر، DataSource مشترک و پایدار برای همه نودها لازم است.

---

## ۱۱. عیب‌یابی

| خطا یا نشانه | علت محتمل | اقدام |
|---|---|---|
| Connector در UI نمایش داده نمی‌شود | Bundle نصب نشده یا OSGi آن فعال نشده | مسیر dropins، لاگ OSGi و Import-Package را بررسی کنید. |
| خطای RealmService یا UserRealm | سرویس UserStore در OSGi در دسترس نیست یا tenant context وجود ندارد | وضعیت RealmService و لاگ زمان Provisioning را بررسی کنید. |
| FIRSTNAME/LASTNAME به‌روزرسانی نمی‌شود | خواندن Claim کامل از UserStore ممکن نیست یا Claim URI اشتباه است | URIهای Claim و دسترسی UserStore را بررسی کنید. |
| خطای JNDI یا DataSource در حالت BULK/HYBRID | DataSource پیش‌فرض WSO2 در دسترس نیست | وجود jdbc/WSO2CarbonDB را در WSO2 بررسی کنید. |
| عملیات در صف باقی مانده است | Stalwart در دسترس نیست، مجوز کافی نیست یا خطای موقت رخ داده | وضعیت صف، زمان تلاش بعدی و خطای آخر را بررسی کنید. |
| عملیات در DEAD است | خطای دائمی یا پایان ۲۰ تلاش | مقدار LAST_ERROR و پاسخ SCIM را بررسی و پس از رفع علت، عملیات را طبق رویه عملیاتی مجدداً ثبت کنید. |
| HTTP 401 | Bearer Token یا مجوز ناکافی | API Key و مجوزهای حساب Provisioning را بررسی کنید. |
| HTTP 404 و domain does not exist | دامنه در Stalwart موجود نیست یا tenant اشتباه است | دامنه و tenant را بررسی کنید. |
| HTTP 409 userName already exists | userName متعلق به externalId دیگری است | حساب را بررسی کنید؛ کانکتور اجازه تصاحب حساب با externalId متفاوت را نمی‌دهد. |
| خطای Bulk و fallback | endpoint Bulk پشتیبانی نمی‌شود | در صورت فعال بودن fallback، همان رکوردهای پایدار از endpointهای Individual ارسال می‌شوند. |
| پس از restart صف پردازش نمی‌شود | Connector هنوز مقداردهی نشده، DataSource در دسترس نیست یا دیتابیس/Volume پایدار نیست | اتصال Provisioning، JNDI و محل ذخیره دیتابیس را بررسی کنید. |

---

## ۱۲. آزمون‌های پذیرش

### آزمون‌های خودکار CI

- ساخت Maven و OSGi Bundle با JDK 21؛
- بررسی نسخه Bundle و Declarative Services؛
- بررسی تعداد و توضیحات انگلیسی ۱۹ فیلد UI؛
- بررسی انتقال Bulk POST و وجود bulkId؛
- بازیابی رکورد صف پس از ایجاد مجدد Outbox؛
- ارسال چند عملیات صف‌شده در یک درخواست Bulk؛
- بررسی تأیید جداگانه هر Operation؛
- بررسی صف HYBRID و Bulk PATCH چندعملیاتی؛
- بررسی عدم ارسال درخواست برای تغییر ایمیل اصلی، Alias و username داخلی.

### آزمون‌های واقعی که پیش از Production لازم‌اند

Build و Smoke Test جایگزین اجرای واقعی روی WSO2 و Stalwart نیستند. پیش از Production این موارد باید روی WSO2 IS 7.3.0 و Stalwart Enterprise واقعی آزمایش شوند:

1. فعال‌شدن Bundle و تزریق RealmService در OSGi؛
2. دریافت Claimهای جاری FIRSTNAME/LASTNAME از UserStore برای کاربران PRIMARY و UserStoreهای ثانویه؛
3. اجرای ایجاد، Update، گروه و حذف در هر سه حالت؛
4. قطع Stalwart پس از ثبت عملیات و مشاهده باقی‌ماندن آن در Outbox؛
5. خاموش و روشن کردن WSO2 و مشاهده ادامه پردازش رکوردها؛
6. قطع شبکه پس از ارسال Bulk و بررسی reconcile عملیات POST؛
7. اجرای Bulk با چند Operation و بررسی پاسخ‌های جزئی؛
8. بررسی پایداری دیتابیس و Volume در محیط واقعی Docker/VM؛
9. بررسی رفتار CSV Import از مسیر واقعی WSO2؛
10. تست چندنودی در صورت استفاده از Cluster.

تا پایان این آزمون‌ها، نسخه 2.0.5 باید نسخه توسعه تلقی شود، نه انتشار Production تأییدشده.

---

## ۱۳. تاریخچه نسخه‌ها

### 2.0.5
- حذف Event Handler جداگانه و تمام نیازمندی‌های JVM Property؛
- خواندن Claimهای جاری WSO2 از طریق RealmService داخل همان Connector Bundle؛
- افزودن Outbox پایدار برای تمام عملیات تغییردهنده در حالت BULK؛
- بازیابی عملیات پس از restart و پردازش دوره‌ای صف؛
- ارسال چند عملیات در یک Bulk Request و بررسی نتیجه هر عملیات؛
- استفاده خودکار از jdbc/WSO2CarbonDB؛
- حذف تنظیمات JNDI و Scope از UI؛
- تولید فقط یک JAR قابل نصب؛
- بازنویسی مستندات فارسی و جدول رفتار نهایی.

### 2.0.4
- عدم همگام‌سازی تغییر ایمیل اصلی، Alias، افزودن/حذف Alias و username داخلی WSO2؛
- تغییرات نام در حالت HYBRID با صف پایدار؛
- انگلیسی‌سازی توضیحات فیلدهای UI.

### 2.0.3
- معرفی HYBRID و صف JDBC برای تغییرات نام؛
- تجمیع PATCHهای نام در Bulk چندعملیاتی.

### 2.0.2
- پشتیبانی از SCIM Bulk برای عملیات provisioning.

### 2.0.1
- تثبیت نگاشت externalId و userName و منطق پایه provisioning.

---

## ۱۴. مخزن و مجوز

مخزن پروژه: https://github.com/hsaeidh/stalwart-scim-connector

مجوز: Apache License 2.0
