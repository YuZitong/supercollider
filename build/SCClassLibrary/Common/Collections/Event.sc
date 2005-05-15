// Events are returned by Pattern Streams

Event : Environment {
	classvar defaultParentEvent;
	classvar <parentEvents;
	classvar <partialEvents;

	*new { arg n=8, proto, parent, know=true; 
		^super.new(n, proto, parent, know)
	}
	
	*default {
		^Event.new(8, nil, defaultParentEvent, true);
	}
	*silent { arg dur = 1.0;
		^defaultParentEvent.copy.put(\type, \rest).put(\dur, dur)
	}
	
	next { arg inval; ^composeEvents(inval, this) }
	
	delta {
		_Event_Delta
		^this.primitiveFailed;
		/*
		// implemented by primitive for speed
		var delta;
		delta = this.at('delta');
		if (delta.notNil, { ^delta },{ ^this.at('dur') * this.at('stretch') });
		*/
	}
	play {
		if (parent.isNil) {
			parent = defaultParentEvent;
		};
		this.use {
			this[\play].value;	
		};
		^this.delta
	}
	
	printOn { arg stream, itemsPerLine = 5;
		var max, itemsPerLinem1, i=0;
		itemsPerLinem1 = itemsPerLine - 1;
		max = this.size;
		stream << "( ";
		this.keysValuesDo({ arg key, val; 
			stream <<< key << ": " << val; 
			if ((i=i+1) < max, { stream.comma.space;
				if (i % itemsPerLine == itemsPerLinem1, { stream.nl.space.space });
			});
		});
		stream << " )";
	}
	
	storeOn { arg stream, itemsPerLine = 5;
		var max, itemsPerLinem1, i=0;
		itemsPerLinem1 = itemsPerLine - 1;
		max = this.size;
		stream << "( ";
		this.keysValuesDo({ arg key, val; 
			stream <<< key << ": " <<< val; 
			if ((i=i+1) < max, { stream.comma.space;
				if (i % itemsPerLine == itemsPerLinem1, { stream.nl.space.space });
			});
		});
		stream << " )";
	}
	
	*initClass {
		Class.initClassTree(Server);
		Class.initClassTree(TempoClock);
		this.makeParentEvents;
				
		SynthDef(\default, { arg out=0, freq=440, amp=0.1, pan=0, gate=1;
			var z;
			z = LPF.ar(
					Mix.new(VarSaw.ar(freq + [0, Rand(-0.4,0.0), Rand(0.0,0.4)], 0, 0.3)),
					XLine.kr(Rand(4000,5000), Rand(2500,3200), 1)
				) * Linen.kr(gate, 0.01, amp * 0.7, 0.3, 2);
			OffsetOut.ar(out, Pan2.ar(z, pan));
		}, [\ir]).writeDefFile;
		

	}
	
	*makeParentEvents {		
		// define useful event subsets.
		partialEvents = (
			pitchEvent: (
				mtranspose: 0,
				gtranspose: 0.0,
				ctranspose: 0.0,
				
				octave: 5.0,
				root: 0.0,		// root of the scale
				degree: 0,
				scale: #[0, 2, 4, 5, 7, 9, 11], // diatonic major scale
				stepsPerOctave: 12.0,
				detune: 0.0,		// detune in Hertz
				
				note: #{
					(~degree + ~mtranspose).degreeToKey(~scale, ~stepsPerOctave);
				},
				midinote: #{
					((~note.value + ~gtranspose + ~root) / ~stepsPerOctave + ~octave) * 12.0; 
				},
				freq: #{
					(~midinote.value + ~ctranspose).midicps;
				},
				freqToScale: #{ arg self, freq; // conversion from frequency to scale value
					self.use {
						var midinote;
						midinote = (freq.cpsmidi - ~ctranspose);
						midinote / 12.0 - ~octave * ~stepsPerOctave - ~root - ~gtranspose
					}
				}
			),
	
			durEvent: (
				tempo: nil,
				dur: 1.0,
				stretch: 1.0,
				legato: 0.8,
				sustain: #{ ~dur * ~legato * ~stretch },
				lag: 0.0,
				strum: 0.0
			),
			
			ampEvent: (
				amp: #{ ~db.dbamp },
				db: -20.0,
				velocity: 64, 		// MIDI units 0-127
				pan: 0.0, 			// pan center
				trig: 0.5
			),
			
			serverEvent: (
				server: nil,
	
				synthLib: nil,
				instrument: \default,
				
				group: 0,
				out: 0,
				addAction: 0,
				
				hasGate: nil,
				msgFunc: nil,
				defaultMsgFunc: #{|freq = 440, amp = 0.1, pan = 0, out = 0| [\freq, freq, \amp, amp, \pan, pan, \out, out] },
				
				// for \type \set
				args: #[\freq, \amp, \pan, \trig]
			),
			
			bufferEvent: (
				bufnum: 0,
				filename: "",
				frame: 0,
				numframes: 0,
				numchannels: 1,
				gencmd: \sine1,
				genflags: 7,
				genarray: [1],
				bufpos: 0,
				leaveOpen: 0
			),
			midiEvent: (
				midiEventFunctions: (
					noteOn:  #{ arg chan=0, midinote=60, amp=0.1;
							[chan, midinote, asInteger((amp * 127).clip(0, 127)) ] },					noteOff: #{ arg chan=0, midinote=60, amp=0.1;
							[ chan, midinote, asInteger((amp * 127).clip(0, 127)) ] },
					polyTouch: #{ arg chan=0, midinote=60, polyTouch=125;
											[ chan, midinote, polyTouch ] },
					control: #{ arg chan=0, ctlNum, control=125;
											[chan, ctlNum, control ] },
					program:  #{ arg chan=0, progNum=1; [ chan, progNum ] },
					touch:  #{ arg chan=0, val=125; [ chan, val ] },
					bend:  #{ arg chan=0, val=125; [ chan, val ] },
					allNotesOff: #{ arg chan=0; [chan] },
					smpte:	#{ arg frames=0, seconds=0, minutes=0, hours=0, frameRate=25;
											[frames, seconds, minutes, hours, frameRate] },
					songPtr: #{ arg songPtr; [songPtr] },
					sysex: #{ arg uid, array; [uid, array] } // Int8Array
				),
				midicmd: \noteOn
			),
			
			playerEvent: (
				type: \note,
				play: #{
					var tempo, server;
					
					~finish.value;
					
					server = ~server ?? { Server.default };
					
					tempo = ~tempo;
					if (tempo.notNil) {
						thisThread.clock.tempo = tempo;
					};
					~eventTypes[~type].value(server);
				},
				eventTypes: (
					note_score:#{|server|
						var instrumentName, freqs, lag, dur, strum, sustain, desc, msgFunc;
						var bndl, synthLib, addAction, group, hasGate;
						var score;
						freqs = ~freq = ~freq.value + ~detune;
						score = ~score;
						if (freqs.isKindOf(Symbol).not) {
							~amp = ~amp.value;
							addAction = ~addAction;
							group = ~group;
							lag = ~lag;
							strum = ~strum;
							sustain = ~sustain = ~sustain.value;
							instrumentName = ~instrument.asSymbol;
							msgFunc = ~msgFunc;
							if (msgFunc.isNil) {
								synthLib = ~synthLib ?? { SynthDescLib.global };
								desc = synthLib.synthDescs[instrumentName];
								if (desc.notNil) { 
									hasGate = desc.hasGate;
									msgFunc = desc.msgFunc;
								}{
									hasGate = ~hasGate ? true;
									msgFunc = ~defaultMsgFunc;
								};
							}{
								hasGate = ~hasGate ? true;
							};
							bndl = msgFunc.valueEnvir.flop;
							bndl.do {|msgArgs, i|
								var id, latency;
								
								latency = i * strum + lag;
								id = server.nextNodeID;
								//send the note on bundle
								score.add([~absTime,  [\s_new, instrumentName, 
										id, addAction, group] ++ msgArgs]);
								if (hasGate) {
									// send note off bundle.
									score.add([sustain + ~absTime, [\n_set, id, \gate, 0]]); 
									
								};
							}
						};
					},				
					rest: #{},
					note: #{|server|
						var instrumentName, freqs, lag, dur, strum, sustain, desc, msgFunc;
						var bndl, synthLib, addAction, group, hasGate;
						
						freqs = ~freq = ~freq.value + ~detune;
										
						if (freqs.isKindOf(Symbol).not) {
							~amp = ~amp.value;
							addAction = ~addAction;
							group = ~group;
							lag = ~lag + server.latency;
							strum = ~strum;
							sustain = ~sustain = ~sustain.value;
							instrumentName = ~instrument.asSymbol;
							msgFunc = ~msgFunc;
							if (msgFunc.isNil) {
								synthLib = ~synthLib ?? { SynthDescLib.global };
								desc = synthLib.synthDescs[instrumentName];
								if (desc.notNil) { 
									hasGate = desc.hasGate;
									msgFunc = desc.msgFunc;
								}{
									hasGate = ~hasGate ? true;
									msgFunc = ~defaultMsgFunc;
								};
							}{
								hasGate = ~hasGate ? true;
							};
						//	~hasGate = hasGate;
							bndl = msgFunc.valueEnvir.flop;
							bndl.do {|msgArgs, i|
								var id, latency;
								
								latency = i * strum + lag;
								id = server.nextNodeID;
								
								//send the note on bundle
								server.sendBundle(latency, [\s_new, instrumentName, id, addAction, group] ++ msgArgs); 
										
								if (hasGate) {
									// send note off bundle.
									thisThread.clock.sched(sustain) { 
										server.sendBundle(latency, [\n_set, id, \gate, 0]); 
									};
								};
							}
						};
					},
					on: #{|server|
						var instrumentName, freqs, lag, dur, strum, desc, msgFunc;
						var bndl, synthLib, addAction, group;
						
						freqs = ~freq = ~freq.value + ~detune;
										
						if (freqs.isKindOf(Symbol).not) {
							~amp = ~amp.value;
							addAction = ~addAction;
							group = ~group;
							lag = ~lag + server.latency;
							strum = ~strum;
							~sustain = ~sustain.value;
							synthLib = ~synthLib.value;
							instrumentName = ~instrument.asSymbol;
							msgFunc = ~msgFunc ?? {
								synthLib = ~synthLib ?? { SynthDescLib.global };
								desc = synthLib.synthDescs[instrumentName];
								if (desc.notNil) { 
									desc.msgFunc;
								}{
									~defaultMsgFunc;
								};
							};
							bndl = ([\s_new, instrumentName, ~id, addAction, group] ++ msgFunc.valueEnvir).flop;
							bndl.do {|msgArgs, i|
								var latency;
								
								latency = i * strum + lag;
								
								//send the note on bundle
								server.sendBundle(latency, msgArgs);
							}
						};
					},
					set: #{|server|
						var instrumentName, freqs, lag, dur, strum, sustain, desc, bndl, msgFunc;
						freqs = ~freq = ~freq.value + ~detune;
										
						if (freqs.isKindOf(Symbol).not) {
							~amp = ~amp.value;
							lag = ~lag + server.latency;
							strum = ~strum;
				
							bndl = ([\n_set, ~id] ++ ~args.envirPairs).flop;
							bndl.do {|msgArgs, i|
								var latency;
								
								latency = i * strum + lag;
								
								server.sendBundle(latency, msgArgs); 
							};
						};
					},
					off: #{|server|
						var lag, dur, strum, hasGate;
										
						lag = ~lag + server.latency;
						strum = ~strum;
						hasGate = ~hasGate ? true;
						
						~id.asArray.do {|id, i|
							var latency;
							
							latency = i * strum + lag;
							if(hasGate) {
								server.sendBundle(latency, [\n_set, id, \gate, 0]); 
							} {
								server.sendBundle(latency, [\n_free, id]);
							}
						};
					},
					finish: #{
						~finishEvents.do { |item| item.play }
					},
					
					group: #{|server|
						var lag, dur, strum;
										
						lag = ~lag + server.latency;
						strum = ~strum;
			
						~id.asArray.do {|id, i|
							var latency;
							
							latency = i * strum + lag;
							
							server.sendBundle(latency.post, [\g_new, id, ~addAction, ~group].post); 
						};
					},
	

					kill: #{|server|
						var lag, dur, strum;
										
						lag = ~lag + server.latency;
						strum = ~strum;
			
						~id.asArray.do {|id, i|
							var latency;
							
							latency = i * strum + lag;
							
							server.sendBundle(latency.post, [\n_free, id].post); 
						};
					},
	
					bus: #{|server|
						var lag, array;
						lag = ~lag + server.latency;
						array = ~array.asArray;
						server.sendBundle(lag, [\c_setn, ~out, array.size] ++ array);
					},
					
					gen: #{|server|
						var lag, genarray;
						lag = ~lag + server.latency;
						genarray = ~genarray;
						server.sendBundle(lag, [\b_gen, ~bufnum, ~gencmd, ~genflags] ++ genarray);
					},
					load: #{|server|
						var lag;
						lag = ~lag + server.latency;
						server.sendBundle(lag, [\b_allocRead, ~bufnum, ~filename, ~frame, ~numframes]);
					},
					read: #{|server|
						var lag;
						lag = ~lag + server.latency;
						server.sendBundle(lag, [\b_read, ~bufnum, ~filename, ~frame, ~numframes, ~bufpos, ~leaveOpen]);
					},
					alloc: #{|server|
						var lag;
						lag = ~lag + server.latency;
						server.sendBundle(lag, [\b_alloc, ~bufnum, ~numframes, ~numchannels]);
					},
					free: #{|server|
						var lag;
						lag = ~lag + server.latency;
						server.sendBundle(lag, [\b_free, ~bufnum]);
					},
					
					midi: #{|server|
						var freqs, lag, dur, sustain, strum;
						var tempo, bndl, midiout, hasHate, midicmd;
						
						freqs = ~freq = ~freq.value + ~detune;
						
						tempo = ~tempo;
						if (tempo.notNil) {
							thisThread.clock.tempo = tempo;
						};
										
						if (freqs.isKindOf(Symbol).not) {
							~finish.value;
							~amp = ~amp.value;
							strum = ~strum;
							lag = ~lag + ~server.latency;
							sustain = ~sustain = ~sustain.value;
							midiout = ~midiout.value;
							hasHate = ~hasGate ? true;
							midicmd = ~midicmd;
							bndl = ~midiEventFunctions[midicmd].valueEnvir.asCollection;
							
							bndl = bndl.flop;
							
							bndl.do {|msgArgs, i|
									var latency;
									
									latency = i * strum + lag;
									
									if(latency == 0.0) {
										midiout.performList(midicmd, msgArgs)
									} {
										thisThread.clock.sched(latency, {
											midiout.performList(midicmd, msgArgs);
										})
									};
									if(hasHate and: {�midicmd === \noteOn }) {
										thisThread.clock.sched(sustain + latency, { 
											midiout.noteOff(*msgArgs)
										});
									};
							};
						}
					};
				)
			)
		);
		
		parentEvents = (
			default: ().putAll(
				partialEvents.pitchEvent, 
				partialEvents.ampEvent, 
				partialEvents.durEvent, 
				partialEvents.bufferEvent,
				partialEvents.serverEvent,
				partialEvents.playerEvent,
				partialEvents.midiEvent
			)
		);
		
		defaultParentEvent = parentEvents.default;
	}
}

