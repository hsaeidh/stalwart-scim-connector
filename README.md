# راهنمای فارسی کانکتور WSO2 به Stalwart از طریق SCIM 2.0

[![Build](https://github.com/hsaeidh/stalwart-scim-connector/actions/workflows/build.yml/badge.svg?branch=feature/2.0.4)](https://github.com/hsaeidh/stalwart-scim-connector/actions/workflows/build.yml)

- **نسخه:** 2.0.4-SNAPSHOT
- **شاخه:** feature/2.0.4
- **نوع Connector در WSO2:** stalwart-scim
- **سازگاری هدف:** WSO2 Identity Server 7.3.x و Stalwart Enterprise با قابلیت SCIM
- **پروتکل:** SCIM 2.0 و Bearer Token
- **وضعیت:** نسخه توسعه؛ پیش از بهره‌برداری باید آزمون‌های یکپارچه‌سازی واقعی انجام شود.

---

## ۱. هدف و معماری

این پروژه یک کانکتور Outbound برای انتقال رویدادهای مجاز کاربران و عضویت گروه‌های توزیع ایمیل از WSO2 Identity Server به Stalwart است. نام کاربری داخلی WSO2 الزاماً آدرس ایمیل نیست.

| مفهوم | نمونه | کاربرد |
|---|---|---|
| نام کاربری داخلی WSO2 | 1234567890 | شناسه محلی کاربر در WSO2 |
| Claim ایمیل اصلی | person@example.com | فقط برای مقدار اولیه userName هنگام ایجاد |
| SCIM userName | person@example.com | نام کاربری و آدرس اصلی اولیه در Stalwart |
| SCIM externalId | 1234567890 | شناسه پایدار بین دو سامانه |
| SCIM displayName | Person Example | نام نمایشی کاربر |

**سیاست هویتی نسخه 2.0.4:** پس از ایجاد حساب، تغییر آدرس اصلی ایمیل، تغییر یا افزودن یا حذف Alias و تغییر username داخلی WSO2 هیچ تغییری در Stalwart ایجاد نمی‌کند. هیچ درخواست SCIM و هیچ رکوردی در صف HYBRID برای این رویدادها تولید نمی‌شود. userName و externalId موجود در Stalwart در Update تغییر نمی‌کنند.

اجزای اصلی:
1. کانکتور Outbound برای ساخت حساب، وضعیت فعال، نام نمایشی، عضویت گروه و حذف حساب.
2. WSO2 Profile Event Handler برای تشخیص تغییر FIRSTNAME/LASTNAME/DISPLAYNAME و ثبت تصویر کامل Claimها در صف؛ این Handler مستقیماً به Stalwart درخواست نمی‌فرستد.
3. JDBC Durable Outbox به‌عنوان صف پایدار تغییرات نام.
4. HYBRID Worker برای پردازش صف هر پنج دقیقه و ارسال چند PATCH در یک درخواست Bulk.

---

## ۲. حالت‌های ارسال

| حالت | ایجاد کاربر | تغییر نام/نام خانوادگی/DisplayName | وضعیت فعال | عضویت گروه توزیع | حذف کاربر |
|---|---|---|---|---|---|
| INDIVIDUAL | POST مستقل و فوری | PATCH مستقل و فوری | PATCH مستقل و فوری | PATCH مستقل و فوری | DELETE مستقل و فوری |
| BULK | Bulk هم‌زمان تک‌عملیاتی | Bulk هم‌زمان تک‌عملیاتی PATCH | Bulk تک‌عملیاتی PATCH | Bulk تک‌عملیاتی PATCH | Bulk تک‌عملیاتی DELETE |
| HYBRID | POST مستقل و فوری | صف پایدار و Bulk چندعملیاتی هر پنج دقیقه | PATCH مستقل و فوری | PATCH مستقل و فوری | DELETE مستقل و فوری |

INDIVIDUAL از endpoint استاندارد هر منبع استفاده می‌کند. BULK هر عملیات را هم‌زمان از endpoint Bulk و در یک درخواست تک‌عملیاتی می‌فرستد؛ این حالت صف یا تجمیع رویدادها نیست. HYBRID فقط تغییرات نام را صف می‌کند و چند کاربر را در یک درخواست Bulk جمع می‌کند. ایجاد کاربر و سایر عملیات غیرپروفایلی در HYBRID منتظر زمان‌بندی نمی‌مانند.

PATCH روش تغییر منبع SCIM است؛ Bulk روش انتقال یک یا چند عملیات در یک درخواست HTTP است. این دو مفهوم جایگزین یکدیگر نیستند.

---

## ۳. جدول رفتار قطعی نسخه 2.0.4

در مواردی که «هیچ درخواستی» درج شده، کانکتور نه POST، نه PATCH، نه DELETE و نه GET جست‌وجویی انجام می‌دهد و هیچ چیزی نیز در صف HYBRID ثبت نمی‌کند.

| رویداد WSO2 | رفتار Stalwart | روش و زمان ارسال | توضیح |
|---|---|---|---|
| ایجاد کاربر جدید | ایجاد User | فوری؛ POST مستقل در INDIVIDUAL و HYBRID، Bulk تک‌عملیاتی در BULK | ایمیل کامل برای userName اولیه و شناسه پایدار برای externalId لازم است. |
| تغییر FIRSTNAME | تغییر displayName | INDIVIDUAL: PATCH فوری؛ BULK: Bulk تک‌عملیاتی؛ HYBRID: صف و Bulk چندعملیاتی هر پنج دقیقه | شرط OR است؛ تغییر FIRSTNAME به‌تنهایی کافی است. مقدار نهایی از تصویر کامل FIRSTNAME و LASTNAME ساخته می‌شود. |
| تغییر LASTNAME | تغییر displayName | مطابق حالت؛ در HYBRID هر پنج دقیقه | تغییر LASTNAME به‌تنهایی کافی است. |
| تغییر DISPLAYNAME | تغییر displayName | مطابق حالت؛ در HYBRID هر پنج دقیقه | اگر خود DISPLAYNAME تغییر کرده باشد، مقدار جاری آن اولویت دارد. |
| تغییر چند فیلد نام | یک displayName نهایی | مطابق حالت؛ در HYBRID آخرین مقدار معتبر تجمیع می‌شود | از PATCHهای متعارض جلوگیری می‌شود. |
| تغییر آدرس اصلی ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | userName در Stalwart تغییر نمی‌کند. |
| تغییر Alias ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | Alias همگام‌سازی نمی‌شود. |
| اضافه‌کردن Alias ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | Alias جدید به Stalwart اضافه نمی‌شود. |
| حذف Alias ایمیل | هیچ تغییری | هیچ درخواست و هیچ صفی | هیچ PATCH یا DELETE مربوط به Alias ارسال نمی‌شود. |
| تغییر username داخلی WSO2 | هیچ تغییری | هیچ درخواست و هیچ صفی | externalId موجود در Stalwart تغییر نمی‌کند و حساب جدیدی ساخته نمی‌شود. |
| افزودن به گروه Email | فعال‌کردن حساب | PATCH فوری؛ در BULK از Bulk تک‌عملیاتی | گروه Email در Stalwart ساخته یا به‌عنوان گروه SCIM ارسال نمی‌شود؛ فقط active=true. |
| حذف از گروه Email | غیرفعال‌کردن حساب | PATCH فوری؛ در BULK از Bulk تک‌عملیاتی | فقط active=false اعمال می‌شود. |
| تغییر Claim فعال‌بودن | تغییر active | PATCH فوری؛ در BULK از Bulk تک‌عملیاتی | فقط Claim بولی پیکربندی‌شده بررسی می‌شود؛ قابلیت معکوس‌سازی مقدار وجود دارد. |
| افزودن عضویت گروه توزیع دیگر | افزودن عضو به Group موجود | PATCH فوری؛ در BULK از Bulk تک‌عملیاتی | گروه باید از قبل در Stalwart ساخته شده باشد. |
| حذف عضویت گروه توزیع دیگر | حذف عضو از Group موجود | PATCH فوری؛ در BULK از Bulk تک‌عملیاتی | فقط delta عضویت اعمال می‌شود. |
| ساخت/ویرایش/حذف خود گروه WSO2 | هیچ تغییر گروهی | هیچ درخواست گروهی | چرخه عمر گروه‌های Stalwart جداگانه مدیریت می‌شود. |
| حذف کاربر از WSO2 | حذف حساب Stalwart | DELETE فوری؛ در BULK از Bulk تک‌عملیاتی | حذف با غیرفعال‌سازی متفاوت است و ممکن است حذف داده‌های حساب را زمان‌بندی کند. |
| تغییر رمز عبور WSO2 | هیچ تغییری | هیچ درخواست SCIM | رمز عبور توسط کانکتور provision نمی‌شود. |

### قواعد هویت

- externalId کلید پایدار تطبیق کاربر است.
- userName فقط هنگام ایجاد اولیه از Claim ایمیل گرفته می‌شود.
- در Update هیچ‌گاه userName یا externalId تغییر نمی‌کند.
- رویدادهایی که فقط ایمیل، Alias یا username داخلی را تغییر داده‌اند، پیش از هر درخواست SCIM متوقف می‌شوند.
- در رویداد ایجاد تکراری، ابتدا externalId جست‌وجو می‌شود. اگر userName به حسابی دیگر برسد، فقط تطابق دقیق externalId اجازه reconcile می‌دهد. حساب متعلق به شناسه دیگر تصاحب یا بازنویسی نمی‌شود.

---

## ۴. منطق تغییر نام و صف HYBRID

شرط تشخیص تغییر نام OR است:

FIRSTNAME changed OR LASTNAME changed OR DISPLAYNAME changed

- اگر DISPLAYNAME تغییر کرده باشد، مقدار جاری و معتبر DISPLAYNAME استفاده می‌شود.
- اگر FIRSTNAME یا LASTNAME تغییر کرده باشد، تصویر کامل و جدید هر دو Claim از UserStore خوانده می‌شود و displayName از آن‌ها ساخته می‌شود.
- اگر تصویر کامل Claimها ناقص باشد، مقدار قدیمی یا ناقص به Stalwart ارسال نمی‌شود.
- مقدار خالی نباید باعث پاک‌شدن ناخواسته نام نمایشی شود.
- تغییرات متوالی یک کاربر در صف ادغام می‌شوند و آخرین مقدار معتبر باقی می‌ماند.

### صف پایدار

نام جدول: STALWART_SCIM_PROFILE_OUTBOX

ویژگی‌ها:
- ثبت و ادغام اتمیک تغییرات؛
- کلیدگذاری بر اساس scope و externalId؛
- جداسازی مقصد، سازمان، tenant و user store؛
- قفل‌گذاری ردیفی و lease برای جلوگیری از پردازش هم‌زمان یک رکورد توسط چند worker؛
- وضعیت‌های PENDING، IN_FLIGHT، SENT و DEAD؛
- تلاش مجدد محدود با backoff؛
- نگهداری رکوردهای موفق برای ۳۰ روز و حفظ رکوردهای DEAD برای بررسی؛
- عدم ذخیره Bearer Token یا رمز عبور.

در هر اجرا حداکثر ۲۵ تغییر نام دریافت می‌شود. worker هر پنج دقیقه اجرا می‌شود. در استقرار چندگرهی، تمام نودهای WSO2 باید از یک DataSource مشترک و پایدار استفاده کنند.

### قرارداد Bulk چندعملیاتی

هر تغییر، یک عملیات مستقل PATCH روی User موجود است. برای PATCH مستقل به کاربران موجود، bulkId لازم نیست. پاسخ هر عملیات جداگانه بررسی می‌شود؛ موفقیت HTTP کلی به معنی موفقیت همه عملیات نیست.

- فقط عملیات موفق تأیید و از صف خارج می‌شود.
- عملیات ناموفق با وضعیت و جزئیات خطای خودش باقی می‌ماند.
- اگر نتیجه به علت قطع شبکه نامشخص باشد، مقدار جاری Stalwart خوانده می‌شود و سپس درباره تأیید یا retry تصمیم گرفته می‌شود؛ ارسال کورکورانه مجدد انجام نمی‌شود.
- fallback به Individual فقط برای پاسخ قطعی عدم پشتیبانی endpoint یعنی HTTP 404، 405 یا 501 مجاز است.

---

## ۵. نگاشت Claimها

| ویژگی | Claim پیش‌فرض WSO2 | مقصد Stalwart | سیاست |
|---|---|---|---|
| ایمیل اصلی | http://wso2.org/claims/emailaddress | userName | فقط هنگام ایجاد؛ تغییر بعدی نادیده گرفته می‌شود. |
| شناسه داخلی | http://wso2.org/claims/username | externalId | هنگام ایجاد؛ پس از آن ثابت |
| نام | http://wso2.org/claims/givenname | ورودی displayName | با تغییر Claim پردازش می‌شود. |
| نام خانوادگی | http://wso2.org/claims/lastname | ورودی displayName | با تغییر Claim پردازش می‌شود. |
| نام نمایشی | http://wso2.org/claims/displayname | displayName | با تغییر Claim پردازش می‌شود. |
| وضعیت حساب | بدون مقدار پیش‌فرض | active | فقط در صورت پیکربندی Claim بولی |
| عضویت Email | گروه Email | active | گروه SCIM ساخته یا همگام‌سازی نمی‌شود. |

در زمان ایجاد، فقط داده‌های موردنیاز ارسال می‌شوند: schema اصلی User، userName معتبر، externalId پایدار، displayName در صورت وجود و active در صورت پیکربندی. رمز عبور، تلفن، آدرس، عکس، Alias، گروه‌ها و Claimهای نامرتبط ارسال نمی‌شوند.

---

## ۶. پیش‌نیازهای Stalwart

1. نسخه Enterprise و مجوز معتبر Stalwart فعال باشد؛ SCIM قابلیت Enterprise است.
2. دامنه ایمیل از قبل در Stalwart ساخته شده باشد.
3. allowScimProvisioning=true برای دامنه فعال باشد.
4. حساب اختصاصی Provisioning ساخته شود؛ از Administrator عمومی استفاده نکنید.
5. حساب Provisioning در tenant/domain صحیح قرار داشته باشد.
6. مجوزهای SCIM و مجوزهای لازم برای خواندن، ایجاد، به‌روزرسانی و حذف حساب‌های تحت مدیریت اعطا شود.
7. برای حساب Provisioning یک API Key بسازید و آن را به‌عنوان Bearer Token در WSO2 قرار دهید.
8. گواهی TLS توسط JVM مورد استفاده WSO2 مورد اعتماد باشد.
9. اگر Stalwart پشت Reverse Proxy است، URL عمومی و مسیر SCIM از WSO2 قابل دسترسی باشند.

آدرس پایه نمونه:

https://mail.example.com/scim/v2

در Base URL فقط آدرس پایه را وارد کنید؛ /Users، /Groups یا /Bulk را اضافه نکنید.

### بررسی endpoint

~~~bash
curl -i https://mail.example.com/scim/v2/ServiceProviderConfig
~~~

بررسی احراز هویت:

~~~bash
curl -i \
  -H "Authorization: Bearer YOUR_SCIM_TOKEN" \
  -H "Accept: application/scim+json" \
  "https://mail.example.com/scim/v2/Users?count=1"
~~~

دامنه و مجوز SCIM را قبل از اتصال WSO2 بررسی کنید. خطای دامنه ناموجود با تغییر کانکتور رفع نمی‌شود.

---

## ۷. تنظیمات Connector در WSO2

در WSO2 یک Outbound Provisioning Connection از نوع stalwart-scim بسازید.

| Property | مقدار پیش‌فرض | توضیح |
|---|---|---|
| scim-base-url | خالی/نمونه در UI | آدرس پایه SCIM که به /scim/v2 ختم می‌شود. |
| scim-bearer-token | خالی | API Key حساب Provisioning در Stalwart؛ محرمانه نگهداری شود. |
| scim-user-store-domain | خالی | دامنه UserStore در WSO2، در صورت نیاز برای نام‌های qualified. |
| enable-claim-mapping | true | استفاده از نگاشت Claimهای WSO2. |
| username-claim-uri | http://wso2.org/claims/emailaddress | Claim ایمیل اصلی؛ فقط برای ایجاد userName اولیه. |
| external-id-claim-uri | http://wso2.org/claims/username | Claim شناسه پایدار که در externalId قرار می‌گیرد. |
| given-name-claim-uri | http://wso2.org/claims/givenname | Claim نام. |
| family-name-claim-uri | http://wso2.org/claims/lastname | Claim نام خانوادگی. |
| display-name-claim-uri | http://wso2.org/claims/displayname | Claim نام نمایشی. |
| enable-profile-updates | true | فعال‌بودن همگام‌سازی نام نمایشی؛ در HYBRID باید true باشد. |
| profile-update-scim-url | خالی | endpoint پایه جایگزین برای PATCH پروفایل؛ در حالت عادی خالی بماند. |
| max-concurrent-requests | 8 | حداکثر درخواست هم‌زمان؛ بازه ۱ تا ۶۴. |
| active-claim-uri | خالی | Claim بولی وضعیت حساب؛ در صورت خالی بودن از این Claim استفاده نمی‌شود. |
| invert-active-claim | false | معکوس‌کردن مقدار Claim؛ مناسب Claimهایی مانند accountDisabled. |
| email-membership-group | Email | گروه WSO2 که دسترسی ایمیل را اعطا می‌کند. |
| provisioning-mode | INDIVIDUAL | یکی از INDIVIDUAL، BULK یا HYBRID. |
| bulk-fallback-to-individual | true | fallback فقط برای پاسخ قطعی 404/405/501 از Bulk. |
| bulk-request-timeout-ms | 30000 | مهلت درخواست Bulk؛ بازه ۱۰۰۰ تا ۱۲۰۰۰۰ میلی‌ثانیه. |
| bulk-max-payload-bytes | 900000 | حداکثر اندازه بدنه Bulk؛ بازه ۱۰۲۴ تا ۱۰۴۸۵۷۶ بایت. |
| hybrid-outbox-jndi-name | jdbc/WSO2CarbonDB | نام JNDI دیتاسورس پایدار صف HYBRID. |
| hybrid-scope-id | خالی | شناسه جداسازی صف بر اساس مقصد، سازمان، tenant و user store. |

### تنظیم پیشنهادی HYBRID

- provisioning-mode=HYBRID
- enable-profile-updates=true
- hybrid-outbox-jndi-name=jdbc/WSO2CarbonDB
- hybrid-scope-id به شکل targetId|tenantDomain|userStoreDomain

اگر رویداد WSO2 شناسه سازمان را فراهم کند:

targetId|organizationId|tenantDomain|userStoreDomain

targetId باید با JVM property مربوطه یکسان و برای هر endpoint مقصد یکتا باشد.

---

## ۸. تنظیم JVM و Event Handler

برای فعال‌سازی HYBRID این JVM propertyها را به تنظیمات راه‌اندازی WSO2 اضافه کنید:

~~~text
-Dstalwart.scim.hybrid.enabled=true
-Dstalwart.scim.hybrid.outbox.jndi=jdbc/WSO2CarbonDB
-Dstalwart.scim.hybrid.target-id=email.irisadc.ir
~~~

target-id را متناسب با endpoint واقعی تغییر دهید. مقدار JNDI باید با hybrid-outbox-jndi-name برابر باشد.

در repository/conf/deployment.toml:

~~~toml
[[event_handler]]
name = "stalwart.scim.profile.observer"
subscriptions = ["POST_ADD_USER", "POST_SET_USER_CLAIMS"]
~~~

- POST_ADD_USER برای مشاهده رویداد ایجاد است؛ ایجاد Stalwart از مسیر Outbound Connector انجام می‌شود.
- POST_SET_USER_CLAIMS تغییر FIRSTNAME/LASTNAME/DISPLAYNAME را تشخیص داده و تصویر کامل نام را در Outbox ثبت می‌کند.
- تغییر ایمیل اصلی، Alias و username داخلی نباید در صف نام قرار گیرد.

اگر URI سفارشی Claim استفاده می‌کنید، JVM property متناظر externalId، given name، family name و displayName نیز باید با نگاشت اتصال برابر باشد. عدم تطابق باعث رد راه‌اندازی HYBRID می‌شود.

**محدودیت CSV:** رفتار CSV import باید روی WSO2 IS 7.3.0 و مسیر واردسازی واقعی آزمایش شود. تا زمانی که انتشار رویداد مناسب و جلوگیری از رویداد تکراری اثبات نشده، پوشش کامل HYBRID برای CSV تضمین‌شده نیست.

---

## ۹. ساخت و نصب

### پیش‌نیاز Build

- JDK 21
- Apache Maven
- دسترسی به Maven Central و مخزن عمومی Maven مربوط به WSO2

### ساخت

~~~bash
mvn -U clean verify
~~~

پروژه دو ماژول دارد:
- org.stalwart.identity.provisioning.connector.scim
- org.stalwart.identity.provisioning.event.handler

خروجی JAR در پوشه target هر ماژول ساخته می‌شود. نسخه Maven شاخه توسعه 2.0.4-SNAPSHOT است.

### نصب در WSO2 IS 7.3.0

1. JAR کانکتور و JAR مربوط به Event Handler را از Build موفق دریافت کنید.
2. هر دو JAR را در مسیر زیر قرار دهید:

   <WSO2_HOME>/repository/components/dropins/

3. JVM propertyهای لازم را اضافه کنید.
4. تنظیم deployment.toml مربوط به Event Handler را اعمال کنید.
5. WSO2 را restart کنید.
6. لاگ‌های OSGi را بررسی کنید تا هر دو bundle بدون خطای resolution فعال شوند.
7. در کنسول WSO2 بررسی کنید نوع stalwart-scim در فهرست Connectorها دیده شود.
8. ابتدا در محیط آزمایشی ساخت کاربر، تغییر نام، وضعیت حساب و عضویت گروه را جداگانه آزمایش کنید.

در Docker نیز JARها باید در مسیر dropins داخل کانتینر WSO2 قرار گیرند و کانتینر با تنظیمات صحیح restart شود. مسیر WSO2_HOME را با نسخه نصب‌شده تطبیق دهید.

---

## ۱۰. امنیت و مدیریت خطا

- Bearer Token نباید در لاگ یا Outbox ذخیره شود.
- Claim value، رمز عبور و اطلاعات کامل کاربر نباید در لاگ چاپ شوند.
- از HTTPS استفاده کنید و گواهی مقصد را در JVM معتبر کنید.
- فیلترهای SCIM باید escape و پارامترهای URL encode شوند.
- cache شناسه‌ها محدود به endpoint و نوع منبع است و عمر محدود دارد.
- تعداد درخواست‌های هم‌زمان محدود است.
- پاسخ هر عملیات Bulk جداگانه ارزیابی می‌شود.
- HTTP 401 معمولاً به Token یا مجوز حساب SCIM مربوط است.
- HTTP 404 با پیام domain does not exist به دامنه یا tenant در Stalwart مربوط است.
- HTTP 409 فقط در صورت تطابق دقیق externalId قابل reconcile است؛ حساب متعلق به شناسه دیگر تصاحب نمی‌شود.
- خطای مبهم شبکه نباید باعث تکرار کورکورانه POST یا Bulk شود.
- حذف کاربر با غیرفعال‌سازی حساب یکسان نیست؛ DELETE ممکن است حذف داده‌های حساب را زمان‌بندی کند.

---

## ۱۱. آزمون و معیار پذیرش

آزمون‌های کد باید این موارد را پوشش دهند:

1. نوع Connector و ۲۱ فیلد تنظیمات UI؛
2. انگلیسی‌بودن توضیحات فیلدهای UI؛
3. عدم ارسال درخواست برای تغییر ایمیل اصلی، Alias، اضافه/حذف Alias و username داخلی؛
4. ساخت حساب با userName و externalId صحیح؛
5. عدم تغییر userName و externalId در Update؛
6. OR بودن شرط تغییر FIRSTNAME/LASTNAME/DISPLAYNAME؛
7. ساخت displayName از تصویر کامل Claimها؛
8. تجمیع تغییرات یک کاربر در Outbox؛
9. اجرای worker هر پنج دقیقه و ارسال چند PATCH در یک Bulk؛
10. بررسی جداگانه نتیجه هر عملیات Bulk؛
11. بازیابی امن پس از نتیجه مبهم شبکه؛
12. جداسازی scope و جلوگیری از claim هم‌زمان یک رکورد توسط دو worker؛
13. اعمال فوری active و عضویت گروه؛
14. عدم ساخت گروه Email در Stalwart؛
15. جلوگیری از ایجاد حساب تکراری با externalId متفاوت.

### آزمون‌های یکپارچه‌سازی واقعی

موفقیت Build یا Smoke Test جایگزین آزمون واقعی WSO2 و Stalwart نیست. موارد زیر باید در محیط واقعی بررسی شوند:

- فعال‌شدن bundleها و OSGi Declarative Services؛
- تحویل واقعی POST_SET_USER_CLAIMS و POST_ADD_USER؛
- رفتار CSV import؛
- اتصال JNDI به پایگاه داده واقعی WSO2؛
- قفل‌گذاری و بازیابی Outbox در چند نود؛
- مجوزها، tenant/domain و endpoint واقعی Stalwart؛
- Bulk PATCH، ETag، خطاهای جزئی و محدودیت اندازه درخواست.

تا انجام این آزمون‌ها، نسخه 2.0.4 یک نسخه توسعه است و نباید به‌عنوان انتشار تولیدی تأییدشده معرفی شود.

---

## ۱۲. عیب‌یابی

| خطا یا نشانه | علت محتمل | اقدام |
|---|---|---|
| A full email address is required | Claim ایمیل در رویداد ایجاد موجود نیست یا نگاشت اشتباه است | Claim ایمیل را در مسیر ایجاد بررسی کنید؛ تغییر ایمیل نباید POST ایجاد کند. |
| HTTP 401 | Token نامعتبر یا مجوز ناکافی | API Key، نقش‌ها و مجوزهای SCIM را بررسی کنید. |
| HTTP 404 و domain does not exist | دامنه در Stalwart ساخته نشده یا tenant نادرست است | دامنه و tenant حساب Provisioning را بررسی کنید. |
| HTTP 409 userName already exists | userName متعلق به externalId دیگری است یا reconcile شناسه پایدار موفق نشده | منبع موجود و externalId آن را بررسی کنید؛ حساب متعلق به کاربر دیگر نباید تصاحب شود. |
| خطای Bulk درباره bulkId | عملیات POST در Bulk فاقد bulkId است | برای POSTهای Bulk، bulkId لازم است؛ PATCH مستقل به User موجود به آن نیاز ندارد. |
| تغییر نام در صف باقی می‌ماند | رویداد یا تصویر کامل Claimها موجود نیست، دیتاسورس در دسترس نیست یا کاربر در Stalwart پیدا نمی‌شود | لاگ Handler و وضعیت Outbox را بررسی کنید؛ اطلاعات حساس را منتشر نکنید. |
| bundle فعال نمی‌شود | import package یا نسخه API با WSO2 سازگار نیست | خطای OSGi را بررسی و build را با WSO2 IS 7.3.0 تطبیق دهید. |
| تغییر ایمیل یا username اثری در Stalwart ندارد | رفتار مورد انتظار نسخه 2.0.4 | این تغییرات عمداً همگام‌سازی نمی‌شوند. |

---

## ۱۳. موارد خارج از دامنه

این کانکتور موارد زیر را انجام نمی‌دهد:
- همگام‌سازی تغییر آدرس اصلی ایمیل پس از ایجاد؛
- همگام‌سازی Alias، افزودن یا حذف آن؛
- همگام‌سازی تغییر username داخلی WSO2؛
- تغییر userName یا externalId موجود در Stalwart؛
- ایجاد یا حذف گروه‌های Stalwart بر اساس چرخه عمر گروه‌های WSO2؛
- همگام‌سازی رمز عبور؛
- ارسال تلفن، آدرس، عکس، locale، timezone یا Claimهای دلخواه؛
- تضمین پشتیبانی CSV import بدون آزمون واقعی مسیر واردسازی WSO2؛
- تضمین موفقیت عملیاتی Stalwart صرفاً بر اساس موفقیت Build.

---

## ۱۴. تاریخچه نسخه

### نسخه 2.0.4
- تعریف صریح عدم همگام‌سازی ایمیل اصلی، Aliasها و username داخلی WSO2؛
- خروج زودهنگام از مسیر Update برای رویدادهایی که تغییر مجاز برای Stalwart ندارند؛
- افزودن Smoke Test برای رویدادهای ایمیل، Alias و username؛
- انگلیسی‌سازی توضیحات تمام فیلدهای ورودی Connector در UI؛
- هم‌راستاسازی نسخه Maven و metadata ماژول‌ها با 2.0.4؛
- تکمیل مستندات فارسی رفتار، تنظیمات، صف و حالت‌های ارسال.

### نسخه 2.0.3
- معرفی حالت HYBRID؛
- صف پایدار JDBC برای تغییرات نام؛
- تجمیع تغییرات و ارسال Bulk چندعملیاتی هر پنج دقیقه؛
- Event Handler برای خواندن تصویر کامل Claimهای WSO2؛
- retry، lease، dead-letter و بررسی نتیجه عملیات Bulk.

### نسخه 2.0.2
- افزودن انتقال هم‌زمان عملیات از endpoint استاندارد SCIM Bulk؛
- حفظ رفتار پیش‌فرض INDIVIDUAL؛
- بررسی وضعیت هر عملیات در پاسخ Bulk و جلوگیری از retry کورکورانه.

### نسخه 2.0.1
- تثبیت سیاست externalId و userName؛
- استفاده از externalId به‌عنوان شناسه پایدار؛
- تکمیل منطق provisioning و فعال/غیرفعال‌سازی حساب.

---

## ۱۵. مخزن و مجوز

مخزن پروژه: https://github.com/hsaeidh/stalwart-scim-connector

مجوز پروژه Apache License 2.0 است.
