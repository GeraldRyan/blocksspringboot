package com.gerald.ryan.blocks.controller;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;

//select instance_name,b.id,hash,data from blockchain c inner join blocksbychain bc on c.id=bc.blockchain_id inner join block b on bc.chain_id=b.id;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.persistence.NoResultException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.gerald.ryan.blocks.Service.BlockchainService;
import com.gerald.ryan.blocks.Service.TransactionService;
import com.gerald.ryan.blocks.Service.UserService;
import com.gerald.ryan.blocks.Service.WalletService;
import com.gerald.ryan.blocks.entity.Blockchain;
import com.gerald.ryan.blocks.entity.Login;
import com.gerald.ryan.blocks.entity.Transaction;
import com.gerald.ryan.blocks.entity.TransactionPool;
import com.gerald.ryan.blocks.entity.User;
import com.gerald.ryan.blocks.entity.Wallet;
import com.gerald.ryan.blocks.initializors.Config;
import com.gerald.ryan.blocks.initializors.Initializer;
import com.gerald.ryan.blocks.pubsub.PubNubApp;
import com.google.gson.Gson;
import com.pubnub.api.PubNubException;
import com.gerald.ryan.blocks.initializors.Config.*;

/* - DEV ONLY, not JAVADOC
 * Key Data Model Session attributes: On register -- should be logged in, hence
 * everything that login has plus ?nothing else? On Login(success) -- Session:
 * wallet, username, isloggedin=true, failed=false On Login(fail) -- Session:
 * failed=true, msg="various string" On logout -- Session: wallet=null,
 * username=null, isloggedin=false
 * 
 */

@Controller
@SessionAttributes({ "blockchain", "wallet", "username", "isloggedin", "user", "msg", "transactionpool", "pubsubapp" })
public class HomeController {
	// This is not Inversion of Control/Loose coupling? Could refactor?
	UserService userService = new UserService();

	public HomeController() throws InterruptedException {
	}

	@ModelAttribute("isloggedin")
	public boolean isLoggedIn() {
		return false;
	}

	@ModelAttribute("blockchain")
	// this is probably right way to do it, this side of Dependency Injection.
	// Initialized right away to avoid buggy sql call dependencies
	public Blockchain bootupOrCreateBlockchain() {
		Blockchain bc = new BlockchainService().getBlockchainService("beancoin");
		if (bc == null) {
			bc = new BlockchainService().newBlockchainService("beancoin");
			Initializer.loadBC("beancoin");
		}
		return new BlockchainService().getBlockchainService("beancoin");
	}

	@ModelAttribute("transactionpool")
	public TransactionPool initTransactionPool() {
		return new TransactionPool();
	}

	/**
	 * 
	 * PubNub pubsub provider. Can be instantiated as needed for broadcast, but as
	 * it is also a listener, should be instantiated right away as session variable
	 * in order to responsond to incoming messages (part of being part of a
	 * community. Hoping this is the right method of doing so
	 * 
	 * @return
	 * @throws InterruptedException
	 */
	@ModelAttribute("pubsubapp")
	public PubNubApp startupApp() throws InterruptedException {
		if (Config.LISTENING) {
			return new PubNubApp();
		}
		return null;
	}

	@GetMapping("")
	public String showIndex(Model model) {
		return "index";
	}

	@GetMapping("/login")
	public String showLoginPage(Model model, @ModelAttribute("login") Login login) {
		if ((boolean) model.getAttribute(("isloggedin"))) {
			return "redirect:/";
		}
		return "login/login";
	}

	@PostMapping("/login")
	public String processLogin(Model model, @ModelAttribute("login") Login login, HttpServletRequest request,
			HttpServletResponse response) {
		String result = validateUserAndPassword(login.getUsername(), login.getPassword());
		if (result == "true") {
			model.addAttribute("username", login.getUsername());
			model.addAttribute("isloggedin", true);
			model.addAttribute("user", new UserService().getUserService(login.getUsername()));
			model.addAttribute("failed", false);
			model.addAttribute("wallet", new WalletService().getWalletService(login.getUsername()));
		} else if (result == "user not found") {
			System.out.println("User not found in records");
			model.addAttribute("failed", true);
			model.addAttribute("msg", "User not found. Please try again");
		} else {
			System.err.println("Password not correct");
			model.addAttribute("failed", true);
			model.addAttribute("msg", "Password incorrect. Please try again");
		}
		return "index";
	}

	@GetMapping("/logout")
	public String logOut(Model model, HttpServletRequest request) {
		model.addAttribute("isloggedin", false);
		model.addAttribute("wallet", null);
		model.addAttribute("username", null);
		HttpSession httpSession = request.getSession();
		return "redirect:/";
	}

	@GetMapping("/transactionpool")
	public String getTransactionPool(Model model) {
		List<Transaction> transactionList = new TransactionService().getAllTransactionsAsTransactionList();
		model.addAttribute("transactionpoollist", transactionList);
		return "transactionpool";
	}

	@PostMapping("/transactionpool")
	@ResponseBody
	public String postTransactionPool(Model model) {

		TransactionPool pool = new TransactionService().getAllTransactionsAsTransactionPoolService();
		if (pool.getMinableTransactionDataString() == null) {
			return "No transactions in the pool. Tell your friends to make transactions";
		}
		String transactionData = pool.getMinableTransactionDataString();
		return pool.getMinableTransactionDataString();
	}

	public String validateUserAndPassword(String username, String password) {
		User user = userService.getUserService(username);
		if (user == null) {
			return "user not found";
		}
		if (user.getPassword().equalsIgnoreCase(password)) {
			return "true";
		}
		return "false";
	}

	/**
	 * 404 pages redirect to home
	 */
	@ControllerAdvice
	public class ControllerAdvisor {
		@ExceptionHandler(NoHandlerFoundException.class)
		public String handle(Exception ex) {
			return "404";
		}
	}

}
