package fileSystem;

import static org.junit.Assert.assertNotNull;

import java.io.*;

//import fileSystem.FileSystem.MODE;

/**
 * Unit tests are in {@see TestMyFileSystem}. See MyFileSystem.java.
 */
public class MyFileSystem implements FileSystem {
	public Disk disk = new Disk();
	public FileTable fileTable = new FileTable();
	public SuperBlock superBlock = new SuperBlock();
	public FreeMap freeMap;

	/**
	 * Reading from or writing to a file.
	 */
	public enum MODE {
		w, r
	};

	/**
	 * Construct a new FileSystem. You are responsible for calling formatDisk on the
	 * new FileSystem if necessary.
	 */
	public MyFileSystem() throws IOException {
		disk.read(0, superBlock);
		initFreeMap();
	}

	public int formatDisk(int size, int isize) throws IOException {
		// The total size of the file system cannot be larger than the
		// maximum size of the disk.
		//
		if (size > Disk.NUM_BLOCKS) {
			System.err.println("Size exceeds disk size of " + Disk.NUM_BLOCKS);
			return -1;
		}

		// Calculate the number of blocks needed for the freemap (may
		// be 0 if the entire free map fits within the superblock.
		//
		int extra = (size - isize - 1) - superBlock.freeMap.length * 8;
		int msize = (int) Math.max(0, Math.ceil(extra / 8.0 / Disk.BLOCK_SIZE));

		// We require that the size of the metadata not exceed the
		// size of the file system.
		//
		if (size - msize - isize - 1 < 0) {
			System.err.println("Metadata will not fit in file system");
			return -1;
		}

		// Initialize and write the superblock.
		superBlock.size = size;
		superBlock.isize = isize;
		superBlock.msize = msize;
		disk.write(0, superBlock);

		// Write empty FreeMapBlocks (if needed) and InodeBlocks, with
		// FreeMapBlocks immediately following the SuperBlock and
		// InodeBlocks immediately following the FreeMapBlocks.
		//
		if (superBlock.mblock0() > 0)
			for (int i = superBlock.mblock0(); i < superBlock.iblock0(); ++i)
				disk.write(i, new FreeMapBlock());
		for (int i = superBlock.iblock0(); i < superBlock.dblock0(); ++i)
			disk.write(i, new InodeBlock());

		// Set up the free map again (because we changed file system
		// metadata since the constructor was called).
		//
		initFreeMap();

		return 0;
	}

	public int shutdown() throws IOException {
		// Save any free map blocks that haven't been written
		freeMap.save();

		// Close any open files
		for (int fd = 0; fd < FileTable.MAX_FILES; ++fd)
			if (fileTable.isValid(fd))
				close(fd);

		// Stop the disk and end
		disk.stop(false);
		return 0;
	}

	public int create() throws IOException {
		// Try to get a free file descriptor.
		//
		int fd = fileTable.allocate();
		if (fd < 0)
			return -1;

		// Try to find an inode for the new file.
		//
		InodeBlock block = new InodeBlock();
		int inumber = 1; // inumbers start at 1, not 0
		for (int n = superBlock.iblock0(); n < superBlock.dblock0(); ++n) {
			disk.read(n, block);
			for (int o = 0; o < InodeBlock.COUNT; ++o, ++inumber) {
				if (block.inodes[o].flags == 0) {
					block.inodes[o].allocate();
					fileTable.add(block.inodes[o], inumber, fd);
					disk.write(n, block);
					return fd;
				}
			}
		}

		// Could not find a free inode, so release our file
		// descriptor, print an error message, and finish.
		//
		fileTable.free(fd);
		System.err.println("Out of files");
		return -1;
	}

	public int open(int inumber) throws IOException {
		if (!inumberIsValid(inumber))
			return -1;

		// Try to get a free file descriptor.
		//
		int fd = fileTable.allocate();
		if (fd < 0)
			return -1;

		// Get the requested inode from disk.
		//
		InodeBlock inodeBlock = new InodeBlock();
		disk.read(inumberToBlockNum(inumber), inodeBlock);
		Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

		// If the inode is allocated, associate the inode with the
		// file descriptor, and then return the fd. Otherwise, there
		// was an error so we should release the file descriptor and
		// return -1.
		//
		if (inode.flags != 0) {
			fileTable.add(inode, inumber, fd);
			return fd;
		}
		fileTable.free(fd);
		System.err.println("File " + inumber + " does not exist");
		return -1;
	}

	public int inumber(int fd) throws IOException {
		return fileTable.getInumber(fd);
	}

	public int read(int fd, byte[] buffer) throws IOException {
		if (!fileDescriptorIsValid(fd))
			return -1;

		DirectBlock block;
		int len, off = 0, limit = getReadLimit(fd, buffer.length);
		for (off = 0; off < limit; off += len) {
			block = getDirectBlock(fd, MODE.r);
			len = block.copyTo(buffer, off); // may copy some garbage in
			seek(fd, len, Whence.SEEK_CUR);
		}
		return limit;
	}

	public int write(int fd, byte[] buffer) throws IOException {
		if (!fileDescriptorIsValid(fd))
			return -1;

		DirectBlock block;
		int len, off = 0;
		for (off = 0; off < buffer.length; off += len) {
			if ((block = getDirectBlock(fd, MODE.w)) == null) {
				System.err.println("File system is full");
				return -1;
			}
			len = block.copyFrom(buffer, off);
			seek(fd, len, Whence.SEEK_CUR);
			updateFileSize(fd);
			block.save();
		}
		return buffer.length;
	}

	public int seek(int fd, int offset, Whence whence) throws IOException {
		if (!fileDescriptorIsValid(fd))
			return -1;

		Inode inode = fileTable.getInode(fd);
		int ptr;

		switch (whence) {
		case SEEK_SET:
			ptr = offset;
			break;
		case SEEK_END:
			ptr = offset + inode.size;
			break;
		case SEEK_CUR:
			ptr = offset + fileTable.getSeekPointer(fd);
			break;
		default:
			return -1;
		}
		if (ptr < 0) {
			System.err.println("Cannot seek to offset < 0");
			return -1;
		}
		fileTable.setSeekPointer(fd, ptr);
		return ptr;
	}

	public int close(int fd) throws IOException {
		if (!fileDescriptorIsValid(fd))
			return -1;

		// Read the InodeBlock in, modify it, and write it back out.
		//
		InodeBlock inodeBlock = new InodeBlock();
		int inumber = fileTable.getInumber(fd);
		disk.read(inumberToBlockNum(inumber), inodeBlock);
		inodeBlock.inodes[inumberToOffset(inumber)] = fileTable.getInode(fd);
		disk.write(inumberToBlockNum(inumber), inodeBlock);

		// Free the file descriptor and return successfully.
		//
		fileTable.free(fd);
		return 0;
	}

	public int delete(int inumber) throws IOException {
		// Disallow deleting of open files.
		//
		int fd;
		if ((fd = fileTable.getFdFromInumber(inumber)) != -1) {
			System.err.println("Cannot delete open file (fd = " + fd + ")");
			return -1;
		}

		// Get inode for this file.
		//
		InodeBlock inodeBlock = new InodeBlock();
		disk.read(inumberToBlockNum(inumber), inodeBlock);
		Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

		// Free all direct blocks in the free map. No need to clear
		// the inode pointers, they are cleared when allocating a new
		// file.
		//
		for (int i = 0; i < inode.ptr.length; ++i)
			if (inode.ptr[i] != 0)
				freeMap.clear(inode.ptr[i]);
		freeMap.save();

		// Mark the inode as free and write it to disk.
		//
		inode.flags = 0;
		disk.write(inumberToBlockNum(inumber), inodeBlock);
		return 0;
	}

	/**
	 * Initialize the freeMap instance. Should be called at the end of the
	 * constructor and from formatDisk.
	 */
	private void initFreeMap() {
		freeMap = new FreeMap(disk, superBlock);
	}

	/**
	 * Ensure that the fd is within the valid range and refers to an open file.
	 * Prints an error message if it is invalid.
	 *
	 * @return boolean true if fd is valid, false otherwise
	 */
	private boolean fileDescriptorIsValid(int fd) {
		if ((fd < 0 || fd >= FileTable.MAX_FILES || fileTable.getInode(fd) == null)) {
			System.err.println("File descriptor " + fd + " is invalid");
			return false;
		}
		return true;
	}

	/**
	 * Ensure that the inumber is within the valid range. Prints an error message if
	 * it is invalid.
	 *
	 * @return boolean true if inumber is valid, false otherwise
	 */
	private boolean inumberIsValid(int inumber) {
		if (inumber <= 0 || inumber >= superBlock.isize * InodeBlock.COUNT) {
			System.err.println("inumber " + inumber + " is invalid");
			return false;
		}
		return true;
	}

	/**
	 * Get a DirectBlock object representing the direct block given the current seek
	 * position in the open file identified by fd. A DirectBlock references the
	 * direct block and offset within that block containing the current seek
	 * position.
	 *
	 * If the current seek position is within a hole or beyond the end of a file,
	 * then if create is true then a block will be allocated to fill the hole. If
	 * there is no more free space in the file system, null will be returned. If the
	 * seek position is in a hole and create is false, then a block containing
	 * zeroes will be returned.
	 *
	 * @param fd
	 *            valid file descriptor of an open file
	 * @param mode
	 *            MODE.w if holes should be filled, MODE.r otherwise (holes will be
	 *            read as blocks of all zeros)
	 * @returns DirectBlock block and offset in that block where the seek position
	 *          of fd can be found
	 */
	private DirectBlock getDirectBlock(int fd, MODE mode) {// FIXME!!!
		Inode inode = fileTable.getInode(fd);
		int seekPtr = fileTable.getSeekPointer(fd);
		int blockNum = seekPtr / Disk.BLOCK_SIZE;
		int blockOff = seekPtr % Disk.BLOCK_SIZE;

		// first indirection
		if (blockNum > 9 && blockNum < (10 + IndirectBlock.COUNT)) {
			// checking if there is an empty hole
			if (inode.ptr[10] == 0) {
				// if reading and there is a hole
				if (mode == MODE.r) {
					return DirectBlock.hole;
				}
				// if writing
				else {
					// create a new indirect block if there is no block already
					// there
					IndirectBlock block = new IndirectBlock();
					int freeSpace1 = freeMap.find();
					int freeSpace2 = freeMap.find();
					// index for single indirection
					int index1 = blockNum - 10;

					// if there is no free space return null
					// TODO: find spaces first and then use a combined if
					// statement with or to return null

					if (freeSpace1 == 0 || freeSpace2 == 0) {
						return null;
					} else {
						// location of the first indirect block
						inode.ptr[10] = freeSpace1;
						// indirect pointer
						block.ptr[index1] = freeSpace2;
						// writing to the disk - add a block at the space 1 and return the new block
						// that is free?
						disk.write(freeSpace1, block);
						// save the spots that have been taken
						freeMap.save();
						// reutrn the new block
						return new DirectBlock(disk, freeSpace2, blockOff, true);
					}
				}
			}
			// if ptr[10] is not 0 in the first place - points to nothing below
			// create the indirect block
			else {
				IndirectBlock indirectBlock = new IndirectBlock();
				disk.read(inode.ptr[10], indirectBlock);
				// TODO change this to an if statement
				boolean fresh;

				if (indirectBlock.ptr[blockNum - 10] == 0) {
					fresh = true;
				} else {
					fresh = false;
				}

				if (fresh) {
					// if read and it's empty return hole
					if (mode == MODE.r) {
						return DirectBlock.hole;
					} else {
						// if full return null
						indirectBlock.ptr[blockNum - 10] = freeMap.find();
						if (indirectBlock.ptr[blockNum - 10] == 0) {
							return null;
						} else {
							// create a direct block
							disk.write(inode.ptr[10], indirectBlock);
							// save it into the map
							freeMap.save();
							// return the new block
							return new DirectBlock(disk, indirectBlock.ptr[blockNum - 10], blockOff, false);
							// TODO: try putting fresh above
						}
					}
				}
				// TODO:this should work if it doesnt then it's the wrong block
				return new DirectBlock(disk, indirectBlock.ptr[blockNum - 10], blockOff, false);
			}
		}

		///////////////////////////////////////////////////////// double indirection
		/////////////////////////////////////////////////////////

		// the condition where we check if the block starts where the previous one ended
		// and adding the square as the end
		// of this block
		if ((blockNum >= (10 + IndirectBlock.COUNT))
				&& blockNum < (10 + IndirectBlock.COUNT + (IndirectBlock.COUNT * IndirectBlock.COUNT))) {
			// checking if there is a hole
			int index1 = (blockNum - (10 + IndirectBlock.COUNT)) / IndirectBlock.COUNT;
			int index2 = (blockNum - (10 + IndirectBlock.COUNT)) % IndirectBlock.COUNT;

			if (inode.ptr[11] == 0) {
				// if read mode tell the OS that there is a hole
				if (mode == MODE.r) {
					return DirectBlock.hole;
				}
				else {
					// we are creating a free indirect block for each indirection with its own free
					// space
					IndirectBlock indirectBlock = new IndirectBlock();
					IndirectBlock indirectBlock2 = new IndirectBlock();
					int freeSpace1 = freeMap.find();
					int freeSpace2 = freeMap.find();
					int freeSpace3 = freeMap.find();
					// TODO: MAKE THIS INTO A METHOD and pass in free space
			
					
					if (freeSpace1 == 0 ||freeSpace2 == 0  || freeSpace3 == 0) {
						return null;
					} else {
						// add the newly created block if the space previously was empty
						inode.ptr[11] = freeSpace1;
						indirectBlock.ptr[index1] = freeSpace2;
						indirectBlock2.ptr[index2] = freeSpace3;
						
						disk.write(freeSpace1, indirectBlock);
						disk.write(freeSpace2, indirectBlock2);
						
						freeMap.save();
						// return the new block at the last free space
						return new DirectBlock(disk, freeSpace3, blockOff, true);
					}			
				}
			}

			// if there is a block
			else {
				// TODO: take this and the one from the if chunk above one level up, no need to
				// repeat thenm
				IndirectBlock indirectBlock = new IndirectBlock();
				disk.read(inode.ptr[11], indirectBlock);

				boolean fresh = false;

				if (indirectBlock.ptr[index1] == 0) {
					fresh = true;
				}
				// if first indirection is false, create locations to store indirect block and
				// return
				if (fresh) {
					if (mode == MODE.r) {
						return DirectBlock.hole;
					} else {
						IndirectBlock indirectBlock2 = new IndirectBlock();
						int freeSpace1 = freeMap.find();
						int freeSpace2 = freeMap.find();

						if (freeSpace1 == 0 || freeSpace2 == 0) {
							return null;
						} else {
							indirectBlock.ptr[index1] = freeSpace1;
							indirectBlock2.ptr[index2] = freeSpace2;
							
							// setting the pointer from the main to the first level
							disk.write(inode.ptr[11], indirectBlock);
							// setting the pointer from the first level to the second level
							disk.write(indirectBlock.ptr[index1], indirectBlock2);
						}
						freeMap.save();
						return new DirectBlock(disk, indirectBlock.ptr[index2], blockOff, false);

					}
					// freshes else ????????????OVDE SI ---> vracam se na vrh da kopiram sve jer je
					// isto
				} else {
					IndirectBlock indirectBlock2 = new IndirectBlock();
					// read what points from first level to the second
					disk.read(indirectBlock.ptr[index1], indirectBlock2);

					boolean fresh2 = indirectBlock2.ptr[index2] == 0;

					if (fresh2) {
						if (mode == MODE.r) {
							return DirectBlock.hole;
						}
						if ((indirectBlock2.ptr[index2] = freeMap.find()) == 0) {
							return null;
						} else {
							disk.write(indirectBlock.ptr[index1], indirectBlock2);
							freeMap.save();
							return new DirectBlock(disk, indirectBlock2.ptr[index2], blockOff, fresh2);
						}
					}
					return new DirectBlock(disk, indirectBlock2.ptr[index2], blockOff, fresh2);
				}
			}
		}
		// TODO the third level redirection/////////////////////////////////////////////////////////
		///////////////////////////////////////////////////////////////////////////////////////////
		//////////////////////////////////////////////////////////////////////////////////////////
		
		if ((blockNum >= (10 + IndirectBlock.COUNT + (IndirectBlock.COUNT * IndirectBlock.COUNT)))
				&& blockNum < ((10 + IndirectBlock.COUNT + (IndirectBlock.COUNT * IndirectBlock.COUNT)
						+ (IndirectBlock.COUNT * IndirectBlock.COUNT * IndirectBlock.COUNT)))) {

			// checking if there is a hole
			// obtain the spot of the node TODO: ASK WHY
			// substract from block num whatever was second indirectrion
			int index1 = blockNum - 10 - (IndirectBlock.COUNT)- (IndirectBlock.COUNT * IndirectBlock.COUNT) / (IndirectBlock.COUNT * IndirectBlock.COUNT);
			int index2 = blockNum - 10 - (IndirectBlock.COUNT)- (IndirectBlock.COUNT * IndirectBlock.COUNT) / IndirectBlock.COUNT; 
					//% IndirectBlock.COUNT;
			int index3 = blockNum - 10 - (IndirectBlock.COUNT)- (IndirectBlock.COUNT * IndirectBlock.COUNT) % IndirectBlock.COUNT;

//			int index1 = (blockNum - ((10 + IndirectBlock.COUNT)) / IndirectBlock.COUNT)/ (IndirectBlock.COUNT * IndirectBlock.COUNT);
//			int index2 = ((blockNum - (10 + IndirectBlock.COUNT)) / IndirectBlock.COUNT) / IndirectBlock.COUNT;
//			int index3 = (blockNum - (10 + IndirectBlock.COUNT)) % IndirectBlock.COUNT;

			if (inode.ptr[12] == 0) {
				// if read mode tell the OS that there is a hole
				if (mode == MODE.r) {
					return DirectBlock.hole;
				}
				// if write mode
				else {
					// we are creating a free indirect block for each indirection with its own free
					// space
					IndirectBlock indirectBlock = new IndirectBlock();
					IndirectBlock indirectBlock2 = new IndirectBlock();
					IndirectBlock indirectBlock3 = new IndirectBlock();
					int freeSpace1 = freeMap.find();
					int freeSpace2 = freeMap.find();
					int freeSpace3 = freeMap.find();
					int freeSpace4 = freeMap.find();
					// TODO: MAKE THIS INTO A METHOD and pass in free space

					if (freeSpace1 == 0 || freeSpace2 == 0 || freeSpace3 == 0 || freeSpace4 == 0) {
						return null;
					}

					// add the newly created block if the space previously was empty
					inode.ptr[12] = freeSpace1;
					indirectBlock.ptr[index1] = freeSpace2;
					indirectBlock2.ptr[index2] = freeSpace3;
					indirectBlock3.ptr[index3] = freeSpace4;
					
					// write the first block to the space 1
					disk.write(freeSpace1, indirectBlock);
					disk.write(freeSpace2, indirectBlock2);
					disk.write(freeSpace3, indirectBlock3);
					
					
					freeMap.save();
					// return the new block at the last free space
					return new DirectBlock(disk, freeSpace4, blockOff, true);
				}
			}

			else {
				// first indirect block exists 
				IndirectBlock indirectBlock = new IndirectBlock();

				disk.read(inode.ptr[12], indirectBlock);

				boolean fresh = false;
				if (indirectBlock.ptr[index1] == 0) {
					fresh = true;
				}

				if (fresh) {
					if (mode == MODE.r) {
						return DirectBlock.hole;
					} else {
						IndirectBlock indirectBlock2 = new IndirectBlock();
						IndirectBlock indirectBlock3 = new IndirectBlock();
						int freeSpace1 = freeMap.find();
						int freeSpace2 = freeMap.find();
						int freeSpace3 = freeMap.find();
						
						// first block attached to inode
						if (freeSpace1 == 0 || freeSpace2 == 0 || freeSpace3 == 0) {
							return null;
						} else {
							// second indirect block does not exit
							// set first IB.ptr[index1] = freespace1
							// write IB to disk
							indirectBlock.ptr[index1] = freeSpace1;
							indirectBlock2.ptr[index2] = freeSpace2;
							disk.write(indirectBlock.ptr[index1], indirectBlock2);
							disk.write(indirectBlock2.ptr[index2], indirectBlock3);
						}
						freeMap.save();
						// TODO: change false to true since
						return new DirectBlock(disk, indirectBlock3.ptr[index3], blockOff, false);
						
					}
				} else {
					// now we know that the first one exists need to create three blocks down which
					// is the copy of the double indirection
					
					// create third indirect block 
					// create indirect block
					
					IndirectBlock indirectBlock2 = new IndirectBlock();
					disk.read(indirectBlock.ptr[index1], indirectBlock2);

					boolean fresh2 = false;
					// TODO: check what index is here
					if (indirectBlock2.ptr[index2] == 0) {
						fresh2 = true;
					}

					if (fresh2) {
						if (mode == MODE.r) {
							return null;
						} else {
							
							// create 3rd indirect block
							// create indirect block
							
							IndirectBlock indirectBlock3 = new IndirectBlock();
							int freeSpace1 = freeMap.find();
							int freeSpace2 = freeMap.find();

							if (freeSpace1 == 0||freeSpace2 == 0) {
								return null;
							}
							
							indirectBlock2.ptr[index2] = freeSpace1;
							indirectBlock3.ptr[index3] = freeSpace2;
							
							disk.write(indirectBlock2.ptr[index2], indirectBlock3);
							freeMap.save();

							return new DirectBlock(disk, indirectBlock3.ptr[index3], blockOff, true);
						}
					} else {
						IndirectBlock indirectBlock3 = new IndirectBlock();
						disk.read(indirectBlock2.ptr[index2], indirectBlock3);

						boolean fresh3 = false;

						if (indirectBlock3.ptr[index3] == 0) {
							fresh3 = true;
						}

						if (fresh3) {
							if (mode == MODE.r) {
								return DirectBlock.hole;
							}
							
							if ((indirectBlock3.ptr[index3] = freeMap.find()) == 0) {
								return null;
							} else {
								disk.write(indirectBlock2.ptr[index2], indirectBlock3);
								freeMap.save();
								return new DirectBlock(disk, indirectBlock3.ptr[index3], blockOff, true);
							}
						}
						return new DirectBlock(disk, indirectBlock3.ptr[index3], blockOff, fresh3);
					}

				}
			}
		}

		// from 0 to 9
		boolean fresh = inode.ptr[blockNum] == 0;
		if (fresh) {
			if (mode == MODE.r) {
				return DirectBlock.hole;
			} else if ((inode.ptr[blockNum] = freeMap.find()) == 0) {
				return null;
			}
		}

		return new DirectBlock(disk, inode.ptr[blockNum], blockOff, fresh);
	}

	/**
	 * Convert an inumber to the number of the InodeBlock that contains it.
	 *
	 * @param inumber
	 *            inumber of inode to locate
	 * @return int block number of InodeBlock
	 */
	private int inumberToBlockNum(int inumber) {
		return superBlock.iblock0() + (inumber - 1) / InodeBlock.COUNT;
	}

	/**
	 * Convert an inumber to its offset within its InodeBlock.
	 *
	 * @param inumber
	 *            inumber of the inode to locate
	 * @return int offset of inode within its InodeBlock
	 */
	private int inumberToOffset(int inumber) {
		return (inumber - 1) % InodeBlock.COUNT;
	}

	/**
	 * Update the size of a file if needed so that it is always at least as large as
	 * the current seek pointer.
	 */
	private void updateFileSize(int fd) {
		int currentSize = fileTable.getInode(fd).size;
		int seekPointer = fileTable.getSeekPointer(fd);
		if (seekPointer > currentSize)
			fileTable.setFileSize(fd, seekPointer);
	}

	/**
	 * Get the maximum number of bytes that can be read from open file fd into a
	 * buffer of length len. If the seek pointer is beyond the end of the file,
	 * always returns 0 (since nothing can be read beyond the end of a file).
	 */
	private int getReadLimit(int fd, int len) {
		int rest = fileTable.getInode(fd).size - fileTable.getSeekPointer(fd);
		return Math.max(0, Math.min(len, rest));
	}
}
