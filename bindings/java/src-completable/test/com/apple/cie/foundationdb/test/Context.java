/*
 * Context.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2013-2018 Apple Inc. and the FoundationDB project authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.cie.foundationdb.test;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.apple.cie.foundationdb.Database;
import com.apple.cie.foundationdb.FDBException;
import com.apple.cie.foundationdb.KeySelector;
import com.apple.cie.foundationdb.Range;
import com.apple.cie.foundationdb.StreamingMode;
import com.apple.cie.foundationdb.Transaction;
import com.apple.cie.foundationdb.tuple.ByteArrayUtil;
import com.apple.cie.foundationdb.tuple.Tuple;

abstract class Context implements Runnable {
	final Stack stack = new Stack();
	final Database db;
	final String preStr;
	int instructionIndex = 0;
	String trName;
	KeySelector nextKey, endKey;
	Long lastVersion = null;
	List<Thread> children = new LinkedList<Thread>();

	static Map<String, Transaction> transactionMap = new HashMap<String, Transaction>();

	Context(Database db, byte[] prefix) {
		this.db = db;
		Range r = Tuple.from(prefix).range();
		this.nextKey = KeySelector.firstGreaterOrEqual(r.begin);
		this.endKey = KeySelector.firstGreaterOrEqual(r.end);

		this.trName = ByteArrayUtil.printable(prefix);
		this.preStr = ByteArrayUtil.printable(prefix);

		newTransaction();
	}

	@Override
	public void run() {
		try {
			executeOperations();
		} catch(Throwable t) {
			// EAT
			t.printStackTrace();
		}
		while(children.size() > 0) {
			//System.out.println("Shutting down...waiting on " + children.size() + " threads");
			final Thread t = children.get(0);
			while(t.isAlive()) {
				try {
					t.join();
				} catch (InterruptedException e) {
					// EAT
				}
			}
			children.remove(0);
		}
	}

	public Transaction getCurrentTransaction() {
		synchronized(Context.transactionMap) {
			return Context.transactionMap.get(this.trName);
		}
	}

	public void updateCurrentTransaction(Transaction tr) {
		synchronized(Context.transactionMap) {
			Context.transactionMap.put(this.trName, tr);
		}
	}

	public void newTransaction() {
		synchronized(Context.transactionMap) {
			Context.transactionMap.put(this.trName, db.createTransaction());
		}
	}

	public void switchTransaction(byte[] trName) {
		synchronized(Context.transactionMap) {
			this.trName = ByteArrayUtil.printable(trName);
			if(!Context.transactionMap.containsKey(this.trName)) {
				newTransaction();
			}
		}
	}

	abstract void executeOperations() throws Throwable;
	abstract Context createContext(byte[] prefix);

	void addContext(byte[] prefix) {
		Thread t = new Thread(createContext(prefix));
		t.start();
		children.add(t);
	}

	StreamingMode streamingModeFromCode(int code) {
		for(StreamingMode x : StreamingMode.values()) {
			if(x.code() == code) {
				return x;
			}
		}
		throw new IllegalArgumentException("Invalid code: " + code);
	}

	void popParams(int num, final List<Object> params, final CompletableFuture<Void> done) {
		while(num-- > 0) {
			Object item = stack.pop().value;
			if(item instanceof CompletableFuture) {
				@SuppressWarnings("unchecked")
				final CompletableFuture<Object> future = (CompletableFuture<Object>)item;
				final int nextNum = num;
				future.whenCompleteAsync(new BiConsumer<Object, Throwable>() {
					@Override
					public void accept(Object o, Throwable t) {
						if(t != null) {
							Throwable root = StackUtils.getRootFDBException(t);
							if(root instanceof FDBException) {
								params.add(StackUtils.getErrorBytes((FDBException)root));
								popParams(nextNum, params, done);
							}
							else {
								done.completeExceptionally(t);
							}
						}
						else {
							if(o == null)
								params.add("RESULT_NOT_PRESENT".getBytes());
							else
								params.add(o);

							popParams(nextNum, params, done);
						}
					}
				});

				return;
			}
			else
				params.add(item);
		}

		done.complete(null);
	}

	CompletableFuture<List<Object>> popParams(int num) {
		final List<Object> params = new LinkedList<Object>();
		CompletableFuture<Void> done = new CompletableFuture<Void>();
		popParams(num, params, done);

		return done.thenApplyAsync(new Function<Void, List<Object>>() {
			@Override
			public List<Object> apply(Void n) {
				return params;
			}
		});
	}
}