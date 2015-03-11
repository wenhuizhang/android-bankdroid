package com.liato.bankdroid.banking.banks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import android.content.Context;
import android.text.InputType;

import com.liato.bankdroid.Helpers;
import com.liato.bankdroid.legacy.R;
import com.liato.bankdroid.banking.Account;
import com.liato.bankdroid.banking.Bank;
import com.liato.bankdroid.banking.Transaction;
import com.liato.bankdroid.banking.exceptions.BankChoiceException;
import com.liato.bankdroid.banking.exceptions.BankException;
import com.liato.bankdroid.banking.exceptions.LoginException;
import com.liato.bankdroid.provider.IAccountTypes;
import com.liato.bankdroid.provider.IBankTypes;

import eu.nullbyte.android.urllib.CertificateReader;
import eu.nullbyte.android.urllib.Urllib;

public class Marginalen extends Bank {
	private static final String TAG = "Marginalen";
    private static final String NAME = "Marginalen Bank";
    private static final String NAME_SHORT = "marginalen";
    private static final String BASE_URL = "https://secure4.marginalen.se";
	private static final int BANKTYPE_ID = IBankTypes.MARGINALEN;
	private static final int INPUT_TYPE_USERNAME = InputType.TYPE_CLASS_PHONE;
    private static final String INPUT_HINT_USERNAME = "ÅÅMMDD-XXXX";

    private static final String INIT_URL = BASE_URL +"/ebank/sec/initLoginEbank.do";
    private static final String LOGIN_URL = BASE_URL +"/ec/login/login.do";
    private static final String SELECT_PROVIDER_URL = BASE_URL + "/ec/login/selectProvider.do";
    private static final String LOGIN_FORM_URL = BASE_URL + "/ec/pintan/processLoginStep1.do";
    private static final String OVERVIEW_URL = BASE_URL + "/ebank/menu/redirectByMenu.do?menuId=myengagements";
    private static final String ACCOUNT_OVERVIEW = BASE_URL + "/ebank/accountview/showAccountDetails.do";
    private static final String TRANSACTIONS_OVERVIEW = BASE_URL + "/ebank/account/fetchTransactionList.do";

    String response;

    public Marginalen(Context context) {
        super(context);
        super.TAG = TAG;
        super.NAME = NAME;
        super.NAME_SHORT = NAME_SHORT;
        super.BANKTYPE_ID = BANKTYPE_ID;
        super.INPUT_TYPE_USERNAME = INPUT_TYPE_USERNAME;
        super.INPUT_HINT_USERNAME = INPUT_HINT_USERNAME;
    }

    public Marginalen(String username, String password, Context context) throws BankException,
            LoginException, BankChoiceException, IOException {
		this(context);
		this.update(username, password);
	}

    @Override
    protected LoginPackage preLogin() throws BankException, IOException {
        urlopen = new Urllib(context, CertificateReader.getCertificates(context, R.raw.cert_marginalen, R.raw.cert_marginalen2));

        response = urlopen.open(INIT_URL);
        List<NameValuePair> initFormData = new ArrayList<>(createInitForm(response).values());

        response = urlopen.open(LOGIN_URL, initFormData, true);

        Map<String, NameValuePair> formData = createSelectProviderForm(response);
        formData.put("id", new BasicNameValuePair("id","PIN"));
        List<NameValuePair> selectProviderForm = new ArrayList<>(formData.values());
        response = urlopen.open(SELECT_PROVIDER_URL, selectProviderForm, true);

        Map<String, NameValuePair> postDataMap = createLoginForm(response);
        postDataMap.put("userId", new BasicNameValuePair("userId",username));
        postDataMap.put("pin", new BasicNameValuePair("pin", password));
        List<NameValuePair> postData = new ArrayList<>(postDataMap.values());
        return new LoginPackage(urlopen, postData, response, LOGIN_FORM_URL);
    }

    @Override
    public Urllib login() throws LoginException, BankException, IOException {
    	LoginPackage lp = preLogin();
    	response = urlopen.open(lp.getLoginTarget(), lp.getPostData());

        if( response.contains("loginForm.errors")) {
            Document doc = Jsoup.parse(response);
            Element error = doc.select("#loginForm p.error").first();
            throw new LoginException(error.text());
        }

	    return urlopen;
    }

    @Override
    public void update() throws BankException, LoginException, BankChoiceException, IOException {
    	super.update();
		if (username == null || password == null || username.length() == 0 || password.length() == 0) {
			throw new LoginException(res.getText(R.string.invalid_username_password).toString());
		}
		urlopen = login();
	    response = urlopen.open(OVERVIEW_URL);
        try {
            Document doc = Jsoup.parse(response);

            Elements checkingsAccounts = doc.select("#checkingAccountsTable tbody tr");
            for (Element checkingsAccount : checkingsAccounts) {
                Element name = checkingsAccount.select("td:first-child a").first();
                String nameString = name.text().trim();
                String id = parseAccountId(name.attr("href"));
                String currency = checkingsAccount.child(2).text().trim();
                String balance = checkingsAccount.child(3).text().trim();
                accounts.add(new Account(nameString, Helpers.parseBalance(balance), id, IAccountTypes.REGULAR, currency));
            }

            Elements loanAccounts = doc.select("#loanAccountsTable tbody tr");
            for (Element loanAccount : loanAccounts) {
                Element name = loanAccount.select("td:first-child a").first();
                String nameString = name.text().trim();
                String id = parseAccountId(name.attr("href"));
                String currency = loanAccount.child(3).text().trim();
                String balance = loanAccount.child(4).text().trim();
                accounts.add(new Account(nameString, Helpers.parseBalance(balance), id, IAccountTypes.REGULAR, currency));
            }
        } catch (Exception e) {
            throw new BankException(e.getMessage(), e);
        }
		if (accounts.isEmpty()) {
			throw new BankException(res.getText(R.string.no_accounts_found).toString());
		}
	    super.updateComplete();
    }

    public void updateTransactions(Account account, Urllib urlopen) throws LoginException,
            BankException, IOException {
		super.updateTransactions(account, urlopen);
        List<Transaction> transactions = new ArrayList<Transaction>();

        HttpResponse httpResponse = urlopen.openAsHttpResponse(ACCOUNT_OVERVIEW + "?back=back&accountId=" + account.getId(), false);
        if(httpResponse.getStatusLine().getStatusCode() != 200) {
            throw new BankException(httpResponse.getStatusLine().toString());
        }
        response = urlopen.open(TRANSACTIONS_OVERVIEW);
        Document doc = Jsoup.parse(response);
        Elements elements = doc.select("#transactions tbody tr");

        for(Element elem : elements) {
            String date = elem.child(0).text().trim();
            String description = elem.child(2).text().trim();
            String amount = elem.child(3).text().trim();
            transactions.add(new Transaction(date, description, Helpers.parseBalance(amount)));
        }
		account.setTransactions(transactions);
	}


    private Map<String, NameValuePair> createInitForm(String response) {
        return parseFormFields(response, "form[name=authForm] input, button");
    }

    private Map<String, NameValuePair> createSelectProviderForm(String response) {
        return parseFormFields(response, "#providerSelectForm input");
    }

    private Map<String, NameValuePair> createLoginForm(String response) {
        return parseFormFields(response, "#loginForm input");
    }

    private String parseAccountId(String url) {
        int idx = url.indexOf("accountId");
        int lastIdx = url.indexOf("&",idx);
        lastIdx = lastIdx == -1 ? url.length() : lastIdx;
        return url.substring(idx, lastIdx).split("=")[1];
    }

    private Map<String, NameValuePair> parseFormFields(String response, String cssSelector) {
        Map<String, NameValuePair> form = new HashMap<>();
        Document doc = Jsoup.parse(response);

        Elements elements = doc.select(cssSelector);
        for (Element element : elements) {
            String name = element.attr("name");
            form.put(name, new BasicNameValuePair(name, element.attr("value")));
        }
        return form;
    }
}
