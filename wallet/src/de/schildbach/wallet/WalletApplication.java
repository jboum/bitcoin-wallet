/*
 * Copyright 2011-2013 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.widget.Toast;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.AutosaveEventListener;
import com.google.bitcoin.store.WalletProtobufSerializer;
import com.google.bitcoin.utils.Locks;

import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class WalletApplication extends Application
{
	private File walletFile;
	private Wallet wallet;
	private Intent blockchainServiceIntent;
	private Intent blockchainServiceCancelCoinsReceivedIntent;
	private Intent blockchainServiceResetBlockchainIntent;
	private ActivityManager activityManager;

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private static final Logger log = LoggerFactory.getLogger(WalletApplication.class);

	@Override
	public void onCreate()
	{
		initLogging();

		StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads().permitDiskWrites().penaltyLog().build());

		Locks.throwOnLockCycles();

		log.info("configuration: " + (Constants.TEST ? "test" : "prod") + ", " + Constants.NETWORK_PARAMETERS.getId());

		super.onCreate();

		CrashReporter.init(getCacheDir());

		activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

		blockchainServiceIntent = new Intent(this, BlockchainServiceImpl.class);
		blockchainServiceCancelCoinsReceivedIntent = new Intent(BlockchainService.ACTION_CANCEL_COINS_RECEIVED, null, this,
				BlockchainServiceImpl.class);
		blockchainServiceResetBlockchainIntent = new Intent(BlockchainService.ACTION_RESET_BLOCKCHAIN, null, this, BlockchainServiceImpl.class);

		walletFile = getFileStreamPath(Constants.WALLET_FILENAME_PROTOBUF);

		migrateWalletToProtobuf();

		loadWalletFromProtobuf();

		backupKeys();

		wallet.autosaveToFile(walletFile, 1, TimeUnit.SECONDS, new WalletAutosaveEventListener());
	}

	private void initLogging()
	{
		final File logDir = getDir("log", Constants.TEST ? Context.MODE_WORLD_READABLE : MODE_PRIVATE);
		final File logFile = new File(logDir, "wallet.log");

		final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

		final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
		filePattern.setContext(context);
		filePattern.setPattern("%d{HH:mm:ss.SSS} [%thread] %logger{0} - %msg%n");
		filePattern.start();

		final RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
		fileAppender.setContext(context);
		fileAppender.setFile(logFile.getAbsolutePath());

		final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<ILoggingEvent>();
		rollingPolicy.setContext(context);
		rollingPolicy.setParent(fileAppender);
		rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%d.log.gz");
		rollingPolicy.setMaxHistory(7);
		rollingPolicy.start();

		fileAppender.setEncoder(filePattern);
		fileAppender.setRollingPolicy(rollingPolicy);
		fileAppender.start();

		final PatternLayoutEncoder logcatTagPattern = new PatternLayoutEncoder();
		logcatTagPattern.setContext(context);
		logcatTagPattern.setPattern("%logger{0}");
		logcatTagPattern.start();

		final PatternLayoutEncoder logcatPattern = new PatternLayoutEncoder();
		logcatPattern.setContext(context);
		logcatPattern.setPattern("[%thread] %msg%n");
		logcatPattern.start();

		final LogcatAppender logcatAppender = new LogcatAppender();
		logcatAppender.setContext(context);
		logcatAppender.setTagEncoder(logcatTagPattern);
		logcatAppender.setEncoder(logcatPattern);
		logcatAppender.start();

		final ch.qos.logback.classic.Logger log = context.getLogger(Logger.ROOT_LOGGER_NAME);
		log.addAppender(fileAppender);
		log.addAppender(logcatAppender);
		log.setLevel(Level.INFO);
	}

	private static final class WalletAutosaveEventListener implements AutosaveEventListener
	{
		public boolean caughtException(final Throwable throwable)
		{
			CrashReporter.saveBackgroundTrace(throwable);
			return true;
		}

		public void onBeforeAutoSave(final File file)
		{
		}

		public void onAfterAutoSave(final File file)
		{
			// make wallets world accessible in test mode
			if (Constants.TEST)
				WalletUtils.chmod(file, 0777);
		}
	}

	public Wallet getWallet()
	{
		return wallet;
	}

	private void migrateWalletToProtobuf()
	{
		final File oldWalletFile = getFileStreamPath(Constants.WALLET_FILENAME);

		if (oldWalletFile.exists())
		{
			log.info("found wallet to migrate");

			final long start = System.currentTimeMillis();

			// read
			wallet = restoreWalletFromBackup();

			try
			{
				// write
				protobufSerializeWallet(wallet);

				// delete
				oldWalletFile.delete();

				log.info("wallet migrated: '" + oldWalletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
			}
			catch (final IOException x)
			{
				throw new Error("cannot migrate wallet", x);
			}
		}
	}

	private void loadWalletFromProtobuf()
	{
		if (walletFile.exists())
		{
			final long start = System.currentTimeMillis();

			FileInputStream walletStream = null;

			try
			{
				walletStream = new FileInputStream(walletFile);

				wallet = new WalletProtobufSerializer().readWallet(walletStream);

				log.info("wallet loaded from: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
			}
			catch (final IOException x)
			{
				log.error("problem loading wallet", x);

				Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

				wallet = restoreWalletFromBackup();
			}
			catch (final RuntimeException x)
			{
				log.error("problem loading wallet", x);

				Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

				wallet = restoreWalletFromBackup();
			}
			finally
			{
				if (walletStream != null)
				{
					try
					{
						walletStream.close();
					}
					catch (final IOException x)
					{
						// swallow
					}
				}
			}

			if (!wallet.isConsistent())
			{
				Toast.makeText(this, "inconsistent wallet: " + walletFile, Toast.LENGTH_LONG).show();

				wallet = restoreWalletFromBackup();
			}

			if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
				throw new Error("bad wallet network parameters: " + wallet.getParams().getId());
		}
		else
		{
			wallet = new Wallet(Constants.NETWORK_PARAMETERS);
			wallet.addKey(new ECKey());

			try
			{
				protobufSerializeWallet(wallet);
				log.info("wallet created: '" + walletFile + "'");
			}
			catch (final IOException x2)
			{
				throw new Error("wallet cannot be created", x2);
			}
		}

		// this check is needed so encrypted wallets won't get their private keys removed accidently
		for (final ECKey key : wallet.getKeys())
			if (key.getPrivKeyBytes() == null)
				throw new Error("found read-only key, but wallet is likely an encrypted wallet from the future");
	}

	private Wallet restoreWalletFromBackup()
	{
		try
		{
			final Wallet wallet = readKeys(openFileInput(Constants.WALLET_KEY_BACKUP_BASE58));

			resetBlockchain();

			Toast.makeText(this, R.string.toast_wallet_reset, Toast.LENGTH_LONG).show();

			log.info("wallet restored from backup: '" + Constants.WALLET_KEY_BACKUP_BASE58 + "'");

			return wallet;
		}
		catch (final IOException x)
		{
			throw new RuntimeException(x);
		}
	}

	private static Wallet readKeys(final InputStream is) throws IOException
	{
		final BufferedReader in = new BufferedReader(new InputStreamReader(is, UTF_8));
		final List<ECKey> keys = WalletUtils.readKeys(in);
		in.close();

		final Wallet wallet = new Wallet(Constants.NETWORK_PARAMETERS);
		for (final ECKey key : keys)
			wallet.addKey(key);

		return wallet;
	}

	public void addNewKeyToWallet()
	{
		wallet.addKey(new ECKey());

		backupKeys();
	}

	public void saveWallet()
	{
		try
		{
			protobufSerializeWallet(wallet);
		}
		catch (final IOException x)
		{
			throw new RuntimeException(x);
		}
	}

	private void protobufSerializeWallet(final Wallet wallet) throws IOException
	{
		final long start = System.currentTimeMillis();

		wallet.saveToFile(walletFile);

		// make wallets world accessible in test mode
		if (Constants.TEST)
			WalletUtils.chmod(walletFile, 0777);

		log.debug("wallet saved to: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
	}

	private void backupKeys()
	{
		try
		{
			writeKeys(openFileOutput(Constants.WALLET_KEY_BACKUP_BASE58, Context.MODE_PRIVATE));
		}
		catch (final IOException x)
		{
			log.error("problem writing key backup", x);
		}

		try
		{
			final String filename = String.format(Locale.US, "%s.%02d", Constants.WALLET_KEY_BACKUP_BASE58,
					(System.currentTimeMillis() / DateUtils.DAY_IN_MILLIS) % 100l);
			writeKeys(openFileOutput(filename, Context.MODE_PRIVATE));
		}
		catch (final IOException x)
		{
			log.error("problem writing key backup", x);
		}
	}

	private void writeKeys(final OutputStream os) throws IOException
	{
		final Writer out = new OutputStreamWriter(os, UTF_8);
		WalletUtils.writeKeys(out, wallet.getKeys());
		out.close();
	}

	public Address determineSelectedAddress()
	{
		final List<ECKey> keys = wallet.getKeys();

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		final String selectedAddress = prefs.getString(Constants.PREFS_KEY_SELECTED_ADDRESS, null);

		if (selectedAddress != null)
		{
			for (final ECKey key : keys)
			{
				final Address address = key.toAddress(Constants.NETWORK_PARAMETERS);
				if (address.toString().equals(selectedAddress))
					return address;
			}
		}

		return keys.get(0).toAddress(Constants.NETWORK_PARAMETERS);
	}

	public void startBlockchainService(final boolean cancelCoinsReceived)
	{
		if (cancelCoinsReceived)
			startService(blockchainServiceCancelCoinsReceivedIntent);
		else
			startService(blockchainServiceIntent);
	}

	public void stopBlockchainService()
	{
		stopService(blockchainServiceIntent);
	}

	public void resetBlockchain()
	{
		// actually stops the service
		startService(blockchainServiceResetBlockchainIntent);
	}

	public final int applicationVersionCode()
	{
		try
		{
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
		}
		catch (NameNotFoundException x)
		{
			return 0;
		}
	}

	public final String applicationVersionName()
	{
		try
		{
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		}
		catch (NameNotFoundException x)
		{
			return "unknown";
		}
	}

	public final String applicationPackageFlavor()
	{
		final String packageName = getPackageName();
		final int index = packageName.lastIndexOf('_');

		if (index != -1)
			return packageName.substring(index + 1);
		else
			return null;
	}

	public int maxConnectedPeers()
	{
		final int memoryClass = activityManager.getMemoryClass();
		if (memoryClass <= Constants.MEMORY_CLASS_LOWEND)
			return 4;
		else
			return 6;
	}
}
