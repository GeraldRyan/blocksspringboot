package com.gerald.ryan.blocks.controller;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.SessionAttributes;

import com.gerald.ryan.blocks.Service.TransactionService;
import com.gerald.ryan.blocks.Service.WalletService;
import com.gerald.ryan.blocks.entity.Transaction;
import com.gerald.ryan.blocks.entity.TransactionPool;
import com.gerald.ryan.blocks.entity.Wallet;
import com.gerald.ryan.blocks.initializors.Config;
import com.gerald.ryan.blocks.initializors.Initializer;
import com.gerald.ryan.blocks.pubsub.PubNubApp;
import com.google.gson.Gson;
import com.pubnub.api.PubNubException;

@Controller
@RequestMapping("wallet")
@SessionAttributes({ "wallet", "latesttransaction", "pool", "username", "blockchain" })

/**
 * ./transact page is for making transactions, GET or POST Completing the form
 * sends you to /transaction GET page, and completes the transaction through
 * requestparams. You can also post to /transaction but it can be buggy with
 * Spring and synchronization
 */
public class WalletController {

	TransactionPool pool = new TransactionService().getAllTransactionsAsTransactionPoolService();
	WalletService ws = new WalletService();

	public WalletController() throws InterruptedException {
	}

	/**
	 * Display wallet console page. Sometimes wallet doesn't load due to session
	 * state. Just click around, log in and out until loads (until fixed)
	 * 
	 * @param model
	 * @return
	 */
	@GetMapping("")
	public String getWallet(Model model) {
		Wallet w = (Wallet) model.getAttribute("wallet");
		if (w == null) {
			return "redirect:/";
		}
		w = ws.updateWalletBalanceService(w);
		model.addAttribute("wallet", w);
		return "wallet/wallet";
	}

	/**
	 * WIP to make a better wallet console.
	 * 
	 * @param model
	 * @return
	 */
	@GetMapping("/betterwallet")
	public String getBetterWallet(Model model) {
		return "wallet/betterwallet";
	}

	@GetMapping("/transact")
	public String getTransact(Model model)
			throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, IOException {
		Wallet w = (Wallet) model.getAttribute("wallet");
		w = ws.getWalletService((String) model.getAttribute("username"));
		model.addAttribute("wallet", w);
		return "wallet/transact";
	}

	/**
	 * This is a simulation of posting transactions from someone who is logged in.
	 * 
	 * { "address":"recipient", "amount": "integer" } in future add { "username":
	 * "username", "password": "password" } to make this official
	 * 
	 * In reality login information would have to be provided to access their wallet
	 * and post the transaction.
	 * 
	 * @param model
	 * @param body
	 * @return
	 * @throws InvalidKeyException
	 * @throws NoSuchAlgorithmException
	 * @throws NoSuchProviderException
	 * @throws IOException
	 * @throws InvalidAlgorithmParameterException
	 * @throws InterruptedException
	 */
	@PostMapping("/transact")
	@ResponseBody
	public String postTransact(Model model, @RequestBody Map<String, Object> body)
			throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, IOException,
			InvalidAlgorithmParameterException, InterruptedException {
		Wallet randomWallet = Wallet.createWallet("anon"); // simulate anon wallet on the wire. This exact name is
		// important as it skips blockchain traversal for balance calculation, which is
		// still buggy with non stored wallets

		Transaction nu = new Transaction(randomWallet, (String) body.get("address"),
				(double) ((Integer) body.get("amount")));
		pool = new TransactionService().getAllTransactionsAsTransactionPoolService();
		model.addAttribute("pool", pool);
		Transaction alt = pool.findExistingTransactionByWallet(nu.getSenderAddress());
		if (alt == null) {
			model.addAttribute("latesttransaction", nu);
			new TransactionService().addTransactionService(nu);
			if (Config.BROADCASTING) {
				broadcastTransaction(nu);
			}
			return nu.toJSONtheTransaction();
		} else {
			Transaction updated = new TransactionService().updateTransactionService(nu, alt);
			model.addAttribute("latesttransaction", updated);
			if (Config.BROADCASTING) {
				broadcastTransaction(updated);
			}
			return updated.toJSONtheTransaction();
		}
	}

	/**
	 * Very important method, this is how you make a transaction with RequestParams
	 * from /transact form posting
	 * 
	 * @param w
	 * @param model
	 * @param address
	 * @param amount
	 * @param request
	 * @return
	 * @throws InvalidKeyException
	 * @throws NoSuchAlgorithmException
	 * @throws NoSuchProviderException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	@RequestMapping(value = "/transaction", method = RequestMethod.GET, produces = "application/json")
	@ResponseBody
	public String postTransaction(@ModelAttribute("wallet") Wallet w, Model model,
			@RequestParam("address") String address, @RequestParam("amount") double amount, HttpServletRequest request)
			throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, IOException,
			InterruptedException {
		Transaction nu = new Transaction(w, address, amount);
		pool = new TransactionService().getAllTransactionsAsTransactionPoolService();
		Transaction alt = pool.findExistingTransactionByWallet(nu.getSenderAddress());
		if (alt == null) {
			System.err.println("Transaction 2 is null. there is no existing transaction of that sender"
					+ nu.getSenderAddress() + "==" + w.getAddress());
			model.addAttribute("latesttransaction", nu);
			new TransactionService().addTransactionService(nu);
			if (Config.BROADCASTING) {
				broadcastTransaction(nu);
			}
			return nu.toJSONtheTransaction();
		} else {
			System.out.println("Existing transaction found!");
			Transaction updated = new TransactionService().updateTransactionService(nu, alt);
			model.addAttribute("latesttransaction", updated);
			if (Config.BROADCASTING) {
				broadcastTransaction(updated);
			}
			return updated.toJSONtheTransaction();
		}
	}

	/**
	 * Dev method, not necessary. Able to post various combinations to make multiple
	 * random type dev transactions. Because over the network and server, this can
	 * get buggy and consume resources. Easier to run dummy transactions in main
	 * method of initializer. Safest to ignore
	 * 
	 * @param model
	 * @param body
	 * @return
	 * @throws InvalidKeyException
	 * @throws NoSuchAlgorithmException
	 * @throws NoSuchProviderException
	 * @throws IOException
	 * @throws InvalidAlgorithmParameterException
	 */
	@PostMapping("/transactt")
	@ResponseBody
	public String postDummyTransactions(Model model, @RequestBody Map<String, Object> body) throws InvalidKeyException,
			NoSuchAlgorithmException, NoSuchProviderException, IOException, InvalidAlgorithmParameterException {

		String numString = (String) body.get("number");
		List<Transaction> list = new ArrayList();
		int n = 1;
		if (numString != null) {
			n = new Random().nextInt(999);
		}
		String fromaddress = (String) body.get("fromaddress");
		if (fromaddress == null) {
			list = new Initializer().postNTransactions(n);
		} else {
			list = new Initializer().postNTransactions(n, fromaddress);
		}

		pool = new TransactionService().getAllTransactionsAsTransactionPoolService();
		model.addAttribute("pool", pool);
		return new Gson().toJson(list);
	}

	/**
	 * Broadcast to pubnub wrapper method
	 * 
	 * @param t
	 * @throws InterruptedException
	 */
	public void broadcastTransaction(Transaction t) throws InterruptedException {
		try {
			new PubNubApp().broadcastTransaction(t);
		} catch (PubNubException e) {
			System.err.println("Problem broadcasting the transaction.");
			e.printStackTrace();
		}
	}

}
