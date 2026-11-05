# Google sign-in setup

BeeKeep uses Android Credential Manager to obtain a Google ID token, then exchanges that token with Supabase Auth. It does not open a browser for this sign-in path.

## 1. Create Google OAuth clients

In the Google Cloud project used for BeeKeep, configure OAuth consent and create:

- A **Web application** OAuth client. This is the server client ID used by Credential Manager and Supabase.
- An **Android** OAuth client for package name `com.beekeep.app`. Add the SHA-1 certificate fingerprints for every build you intend to use. Run `./gradlew signingReport` to view the local debug and release fingerprints.

Do not use the Android client ID as the Credential Manager server client ID.

## 2. Enable Google in Supabase

In the BeeKeep Supabase project, open **Authentication → Providers → Google**. Enable the provider and enter the Web application client ID and its client secret. The client secret belongs only in Supabase; never put it in the Android app, Gradle properties, or source control.

## 3. Configure the Android build

Add the Web application client ID to your ignored local `local.properties` file:

```properties
googleWebClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

The Gradle build also accepts the `googleWebClientId` project property or the `GOOGLE_WEB_CLIENT_ID` environment variable. The ID is a public OAuth client identifier, not a secret.

Without this value, BeeKeep still builds and email/password authentication continues to work, but the Google button displays a configuration message.

## 4. Test

Install the debug build on a device or emulator with Google Play services and a Google account. Test both an existing BeeKeep Google account and a first-time Google sign-in. Confirm that the Supabase user session is created and that BeeKeep keeps its local-data attachment/sync hold in place until the account's local data has been reviewed.
