package com.gerald.ryan.blocks.controller;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.SessionAttributes;

import com.gerald.ryan.blocks.Service.BlockService;
import com.gerald.ryan.blocks.Service.BlockchainService;
import com.gerald.ryan.blocks.Service.TransactionService;
import com.gerald.ryan.blocks.entity.Block;
import com.gerald.ryan.blocks.entity.Blockchain;
import com.gerald.ryan.blocks.entity.Transaction;
import com.gerald.ryan.blocks.entity.TransactionPool;
import com.gerald.ryan.blocks.exceptions.BlocksInChainInvalidException;
import com.gerald.ryan.blocks.exceptions.ChainTooShortException;
import com.gerald.ryan.blocks.exceptions.GenesisBlockInvalidException;
import com.gerald.ryan.blocks.initializors.Config;
import com.gerald.ryan.blocks.initializors.Initializer;
import com.gerald.ryan.blocks.pubsub.PubNubApp;
import com.gerald.ryan.blocks.utilities.TransactionRepr;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.pubnub.api.PubNubException;

@Controller
@SessionAttributes({ "blockchain", "minedblock", "wallet", "pnapp" })
@RequestMapping("blockchain")
public class BlockchainController {

	TransactionService tService = new TransactionService();
	TransactionPool pool = tService.getAllTransactionsAsTransactionPoolService();

	public BlockchainController() throws InterruptedException {
	}

	BlockService blockApp = new BlockService();
	BlockchainService blockchainApp = new BlockchainService();

	/**
	 * Pulls up beancoin blockchain on startup.
	 * 
	 * If no beancoin exists, create one and populate it with initial values
	 * 
	 * Also syncs blockchain so should be updated
	 */
	@ModelAttribute("blockchain")
	public Blockchain addBlockchain(Model model) throws NoSuchAlgorithmException, InterruptedException {
		try {
			Blockchain blockchain = blockchainApp.getBlockchainService("beancoin");
			return blockchain;
		} catch (Exception e) {
			Blockchain blockchain = blockchainApp.newBlockchainService("beancoin");
			Initializer.loadBC("beancoin");
			Blockchain populated_blockchain = blockchainApp.getBlockchainService("beancoin");
			return populated_blockchain;
		}
	}

	@RequestMapping(value = "", method = RequestMethod.GET, produces = "application/json")
	@ResponseBody
	public String serveBlockchain(Model model) throws NoSuchAlgorithmException, InterruptedException,
			ChainTooShortException, GenesisBlockInvalidException, BlocksInChainInvalidException {
		refreshChain(model);
		return ((Blockchain) model.getAttribute("blockchain")).toJSONtheChain();
	}

	@RequestMapping(value = "mine", method = RequestMethod.GET, produces = "application/json")
	@ResponseBody
	public String getMine(@ModelAttribute("blockchain") Blockchain blockchain, Model model)
			throws NoSuchAlgorithmException, PubNubException, InterruptedException {
		pool = tService.getAllTransactionsAsTransactionPoolService();
		if (pool.getMinableTransactionDataString() == null) {
			return "No data to mine. Tell your friends to make transactions";
		}
		String transactionData = "MAIN INSTANCE STUBBED DATA";
		transactionData = pool.getMinableTransactionDataString();
		List<Transaction> tlist = tService.getAllTransactionsAsTransactionList();
		Block new_block = blockchainApp.addBlockService("beancoin", transactionData);
		if (Config.BROADCASTING) {
			new PubNubApp().broadcastBlock(new_block);
		}
		model.addAttribute("minedblock", new_block);
		blockchain = blockchainApp.getBlockchainService("beancoin");
		model.addAttribute("blockchain", blockchain);
		pool.refreshBlockchainTransactionPool(blockchain);
		pool = tService.getAllTransactionsAsTransactionPoolService();
		model.addAttribute("pool", pool);
		return new_block.webworthyJson(tlist);
	}

	/**
	 * This method order the chain properly according to timestamp if for some
	 * reason it pulled it from the database out of order (JPA error)
	 * 
	 * @param model
	 */
	public void refreshChain(Model model) {
		Blockchain newer_blockchain_from_db = blockchainApp.getBlockchainService("beancoin");
		try {
			ArrayList<Block> new_chain = new ArrayList<Block>(newer_blockchain_from_db.getChain());
			System.out.println("RE-SORTING ArrayList<Block>");
			Collections.sort(new_chain, Comparator.comparingLong(Block::getTimestamp));
			((Blockchain) model.getAttribute("blockchain")).setChain(new_chain);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@RequestMapping(value = "/{n}", method = RequestMethod.GET, produces = "application/json")
	@ResponseBody
	public String seeIf(@PathVariable String n, @ModelAttribute("blockchain") Blockchain blockchain, Model model)
			throws NoSuchAlgorithmException, PubNubException, InterruptedException {
		try {
			Block b = ((Blockchain) model.getAttribute("blockchain")).getNthBlock(Integer.valueOf(n));
			if (Integer.valueOf(n) >= 0 && Integer.valueOf(n) <= 5) {
				return new Gson().toJson(b);
			}
			List<TransactionRepr> tr = b.deserializeTransactionData();
			return b.webworthyJson(tr, "plug");
		} catch (IndexOutOfBoundsException e) {
			return "This index doens't exist yet in our chain. Try a different number";
		}

	}

}
